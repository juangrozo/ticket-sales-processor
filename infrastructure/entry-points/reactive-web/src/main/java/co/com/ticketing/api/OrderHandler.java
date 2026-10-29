package co.com.ticketing.api;

import co.com.ticketing.api.dto.OrderResponse;
import co.com.ticketing.api.dto.PlaceOrderRequest;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.usecase.confirmorder.ConfirmOrderUseCase;
import co.com.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.ticketing.usecase.placeorder.PlaceOrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class OrderHandler {

    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final int MAX_CUSTOMER_ID_LENGTH = 64;

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;

    /** Answers 202 as soon as the order is stored and queued; the reservation happens asynchronously. */
    public Mono<ServerResponse> place(ServerRequest request) {
        return Mono.fromCallable(() -> customerId(request))
                .flatMap(customerId -> request.bodyToMono(PlaceOrderRequest.class)
                        .switchIfEmpty(Mono.error(() -> new DomainValidationException("Request body is required")))
                        .flatMap(body -> {
                            if (body.quantity() == null || body.eventId() == null) {
                                return Mono.error(new DomainValidationException("eventId and quantity are required"));
                            }
                            String idempotencyKey = request.headers().firstHeader(IDEMPOTENCY_HEADER);
                            return placeOrderUseCase.place(body.eventId(), customerId, body.quantity(), idempotencyKey);
                        }))
                .flatMap(order -> ServerResponse.accepted()
                        .location(URI.create("/orders/" + order.orderId()))
                        .bodyValue(OrderResponse.from(order)));
    }

    /** Answers 202: the tickets are held as PENDING_CONFIRMATION and the payment runs asynchronously. */
    public Mono<ServerResponse> confirm(ServerRequest request) {
        return Mono.fromCallable(() -> customerId(request))
                .flatMap(customerId -> confirmOrderUseCase.requestConfirmation(request.pathVariable("orderId"), customerId))
                .flatMap(order -> ServerResponse.accepted()
                        .location(URI.create("/orders/" + order.orderId()))
                        .bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> customerId(request))
                .flatMap(customerId -> getOrderUseCase.get(request.pathVariable("orderId"), customerId))
                .flatMap(details -> ServerResponse.ok().bodyValue(OrderResponse.from(details)));
    }

    /** Stand-in for the authenticated principal; with JWT it would come from the token subject. */
    private static String customerId(ServerRequest request) {
        String customerId = request.headers().firstHeader(CUSTOMER_HEADER);
        if (customerId == null || customerId.isBlank() || customerId.length() > MAX_CUSTOMER_ID_LENGTH) {
            throw new DomainValidationException(CUSTOMER_HEADER + " header is required (max " + MAX_CUSTOMER_ID_LENGTH + " characters)");
        }
        return customerId;
    }
}
