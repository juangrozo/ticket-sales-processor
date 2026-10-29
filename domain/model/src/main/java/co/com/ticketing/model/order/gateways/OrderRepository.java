package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

public interface OrderRepository {

    /**
     * Stores the new order, its idempotency record and its audit entry atomically. Fails with
     * {@code DuplicateIdempotencyKeyException} when another request registered the same key first.
     */
    Mono<Order> create(Order order, IdempotencyRecord idempotencyRecord);

    Mono<Order> findById(String orderId);
}
