package co.com.ticketing.usecase.reservetickets;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Consumes an order-placed message: takes the tickets from inventory or rejects the order.
 * Safe to run more than once for the same message (at-least-once delivery): every write is
 * conditional on the order still being PENDING.
 */
@RequiredArgsConstructor
public class ReserveTicketsUseCase {

    private static final System.Logger LOG = System.getLogger(ReserveTicketsUseCase.class.getName());
    static final String INSUFFICIENT_INVENTORY = "Insufficient inventory";

    private final OrderRepository orderRepository;
    private final StateChangeRepository stateChangeRepository;
    private final Clock clock;

    public Mono<Void> reserve(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                        LOG.log(System.Logger.Level.WARNING, "Order {0} not found, message ignored", orderId)))
                .filter(ReserveTicketsUseCase::isPending)
                .flatMap(this::reserveOrReject);
    }

    private static boolean isPending(Order order) {
        if (order.status() == OrderStatus.PENDING) {
            return true;
        }
        LOG.log(System.Logger.Level.INFO, "Order {0} already {1}, duplicate order-placed message ignored",
                order.orderId(), order.status());
        return false;
    }

    private Mono<Void> reserveOrReject(Order order) {
        Instant now = clock.instant();
        Order reserved = order.apply(new Reserve(now));
        List<Ticket> tickets = IntStream.range(0, order.quantity())
                .mapToObj(_ -> Ticket.reserved(UUID.randomUUID().toString(), order.orderId(), order.eventId(), now))
                .toList();

        return stateChangeRepository.apply(StateChange.reservation(order, reserved, tickets))
                .doOnSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                        "Order {0} reserved: PENDING -> AWAITING_CONFIRMATION, {1} tickets RESERVED until {2}",
                        order.orderId(), String.valueOf(order.quantity()), reserved.expiresAt()))
                .onErrorResume(InsufficientInventoryException.class, _ -> stateChangeRepository.apply(
                        StateChange.orderOnly(order, order.apply(new Reject(now, INSUFFICIENT_INVENTORY))))
                        .doOnSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                                "Order {0} rejected: PENDING -> REJECTED, insufficient inventory", order.orderId())))
                // A duplicate message already moved the order: nothing left to do
                .onErrorResume(ConcurrentUpdateException.class, _ -> {
                    LOG.log(System.Logger.Level.INFO, "Order {0} changed concurrently, duplicate message ignored", order.orderId());
                    return Mono.empty();
                });
    }
}
