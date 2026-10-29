package co.com.ticketing.usecase.placeorder;

import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Registers the order and queues it; it never touches the inventory, so it answers fast under
 * load. The consumer of the queue does the reservation.
 */
@RequiredArgsConstructor
public class PlaceOrderUseCase {

    private static final System.Logger LOG = System.getLogger(PlaceOrderUseCase.class.getName());

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final Clock clock;

    public Mono<Order> place(String eventId, String customerId, int quantity, String idempotencyKey) {
        return Mono.fromCallable(() -> IdempotencyRecord.scopedKey(customerId, idempotencyKey))
                .flatMap(scopedKey -> {
                    String requestHash = IdempotencyRecord.hashOf(eventId, customerId, quantity);
                    return idempotencyRepository.findByKey(scopedKey)
                            .flatMap(existing -> replay(existing, requestHash))
                            .switchIfEmpty(Mono.defer(() -> createOrder(eventId, customerId, quantity, scopedKey, requestHash)))
                            // Same key sent twice at the same time: the loser replays the winner's order
                            .onErrorResume(DuplicateIdempotencyKeyException.class, _ -> idempotencyRepository.findByKey(scopedKey)
                                    .flatMap(existing -> replay(existing, requestHash)));
                });
    }

    private Mono<Order> createOrder(String eventId, String customerId, int quantity, String scopedKey, String requestHash) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("Event", eventId)))
                .map(_ -> Order.place(UUID.randomUUID().toString(), eventId, customerId, quantity, clock.instant()))
                .flatMap(order -> orderRepository.create(order,
                        IdempotencyRecord.of(scopedKey, requestHash, order.orderId(), order.createdAt())))
                .flatMap(order -> orderEventPublisher.publishOrderPlaced(order.orderId())
                        // The order is already stored as PENDING; the reconciliation job re-queues it
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Order {0} stored but not queued: {1}",
                                    order.orderId(), error.getMessage());
                            return Mono.empty();
                        })
                        .thenReturn(order));
    }

    private Mono<Order> replay(IdempotencyRecord existing, String requestHash) {
        if (!existing.matches(requestHash)) {
            return Mono.error(new IdempotencyConflictException());
        }
        return orderRepository.findById(existing.orderId());
    }
}
