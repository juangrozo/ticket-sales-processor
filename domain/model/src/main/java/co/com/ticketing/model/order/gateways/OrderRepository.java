package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    /**
     * Stores the new order, its idempotency record and its audit entry atomically. Fails with
     * {@code DuplicateIdempotencyKeyException} when another request registered the same key first.
     */
    Mono<Order> create(Order order, IdempotencyRecord idempotencyRecord);

    Mono<Order> findById(String orderId);

    /** Orders in {@code status} whose last change happened in {@code [from, to]}, at most {@code limit}. */
    Flux<Order> findByStatusChangedBetween(OrderStatus status, Instant from, Instant to, int limit);
}
