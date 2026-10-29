package co.com.ticketing.api.ratelimit;

import co.com.ticketing.api.OrderHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

class RateLimitFilterTest {

    private static final long FROZEN_TIME = 0;

    private final RateLimitFilter filter = new RateLimitFilter(new RateLimiter(2, 1, () -> FROZEN_TIME));
    private final WebTestClient client = WebTestClient
            .bindToRouterFunction(RouterFunctions.route(GET("/events"), _ -> ServerResponse.ok().build())
                    .andRoute(GET("/actuator/health"), _ -> ServerResponse.ok().build()))
            .webFilter(filter)
            .build();

    private WebTestClient.ResponseSpec call(String customerId) {
        return client.get().uri("/events").header(OrderHandler.CUSTOMER_HEADER, customerId).exchange();
    }

    @Test
    void shouldRejectRequestsBeyondTheLimitWithRetryAfter() {
        call("c-1").expectStatus().isOk();
        call("c-1").expectStatus().isOk();

        call("c-1").expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(429);
    }

    @Test
    void anotherCustomerShouldHaveItsOwnLimit() {
        call("c-1");
        call("c-1");

        call("c-2").expectStatus().isOk();
    }

    @Test
    void anonymousClientsShouldBeLimitedByAddress() {
        client.get().uri("/events").exchange().expectStatus().isOk();
        client.get().uri("/events").exchange().expectStatus().isOk();

        client.get().uri("/events").exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void healthChecksShouldNeverBeLimited() {
        for (int i = 0; i < 5; i++) {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }

    @Test
    void shouldRunBeforeRouting() {
        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }
}
