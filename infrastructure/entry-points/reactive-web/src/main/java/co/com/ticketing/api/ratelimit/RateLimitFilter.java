package co.com.ticketing.api.ratelimit;

import co.com.ticketing.api.OrderHandler;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Per-client rate limit for the API. The client is the customer id when present, otherwise the
 * remote address. Limits are per instance; network-level limits per IP belong to the WAF in front
 * of the load balancer. Health and metrics endpoints are never limited.
 */
public class RateLimitFilter implements WebFilter, Ordered {

    private static final byte[] BODY = """
            {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded"}"""
            .getBytes(StandardCharsets.UTF_8);

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public int getOrder() {
        // Before routing and body decoding, so rejected requests cost almost nothing
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        Duration wait = rateLimiter.acquire(clientKey(exchange));
        if (wait.isZero()) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (long) Math.ceil(wait.toMillis() / 1000d))));
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(BODY)));
    }

    private static String clientKey(ServerWebExchange exchange) {
        String customerId = exchange.getRequest().getHeaders().getFirst(OrderHandler.CUSTOMER_HEADER);
        if (customerId != null && !customerId.isBlank()) {
            return "customer:" + customerId;
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
    }
}
