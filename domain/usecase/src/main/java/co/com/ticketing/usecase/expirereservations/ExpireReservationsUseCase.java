package co.com.ticketing.usecase.expirereservations;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.DomainException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.Expire;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Periodic sweep, run by the worker:
 * <ul>
 *   <li>releases reservations past their 10 minutes back to AVAILABLE;</li>
 *   <li>re-queues orders stuck in PENDING or CONFIRMING because their message was never sent.</li>
 * </ul>
 * Every write is conditional, so several workers sweeping at the same time cannot apply a change twice.
 */
@RequiredArgsConstructor
public class ExpireReservationsUseCase {

    private static final System.Logger LOG = System.getLogger(ExpireReservationsUseCase.class.getName());

    /** An order still waiting after this long most likely lost its message. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(1);
    /** Older stuck orders are left for an operator instead of being re-queued forever. */
    public static final Duration GIVE_UP_AFTER = Duration.ofHours(1);
    static final int BATCH_SIZE = 100;
    private static final int CONCURRENCY = 8;

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final StateChangeRepository stateChangeRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final Clock clock;

    public record SweepResult(long expired, long requeuedPending, long requeuedConfirming) {
    }

    public Mono<SweepResult> sweep() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Mono<Long> expired = orderRepository
                    .findByStatusChangedBetween(OrderStatus.AWAITING_CONFIRMATION, Instant.EPOCH,
                            now.minus(Order.RESERVATION_TTL), BATCH_SIZE)
                    .flatMap(order -> expire(order, now), CONCURRENCY)
                    .count();
            Mono<Long> requeuedPending = requeue(OrderStatus.PENDING, now, orderEventPublisher::publishOrderPlaced);
            Mono<Long> requeuedConfirming = requeue(OrderStatus.CONFIRMING, now,
                    orderEventPublisher::publishConfirmationRequested);
            return Mono.zip(expired, requeuedPending, requeuedConfirming)
                    .map(counts -> new SweepResult(counts.getT1(), counts.getT2(), counts.getT3()));
        });
    }

    private Mono<Order> expire(Order order, Instant now) {
        // The index is eventually consistent: re-check the deadline before writing
        if (!order.isReservationExpired(now)) {
            return Mono.empty();
        }
        Order expired = order.apply(new Expire(now));
        return ticketRepository.findByOrderId(order.orderId())
                .collectList()
                .flatMap(tickets -> stateChangeRepository.apply(StateChange.transition(order, expired, tickets,
                        TicketStatus.RESERVED, TicketStatus.AVAILABLE)))
                .doOnSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                        "Order {0} expired: AWAITING_CONFIRMATION -> EXPIRED, tickets back to AVAILABLE", order.orderId()))
                .thenReturn(expired)
                // Confirmed in the meantime, or swept by another worker: nothing to do
                .onErrorResume(DomainException.class, error -> {
                    LOG.log(System.Logger.Level.DEBUG, "Order {0} not expired: {1}", order.orderId(), error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Long> requeue(OrderStatus status, Instant now, Function<String, Mono<Void>> publish) {
        return orderRepository.findByStatusChangedBetween(status, now.minus(GIVE_UP_AFTER), now.minus(STALE_AFTER), BATCH_SIZE)
                .flatMap(order -> publish.apply(order.orderId())
                        .doOnSuccess(_ -> LOG.log(System.Logger.Level.INFO, "Order {0} re-queued: stuck in {1}",
                                order.orderId(), status))
                        .thenReturn(order)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Order {0} could not be re-queued: {1}",
                                    order.orderId(), error.getMessage());
                            return Mono.empty();
                        }), CONCURRENCY)
                .count();
    }

    /** Runs {@link #sweep()} every {@code interval}, never overlapping, until the subscription is cancelled. */
    public Flux<SweepResult> sweepEvery(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(_ -> sweep().onErrorResume(error -> {
                    LOG.log(System.Logger.Level.ERROR, "Sweep failed: {0}", error.getMessage());
                    return Mono.empty();
                }));
    }
}
