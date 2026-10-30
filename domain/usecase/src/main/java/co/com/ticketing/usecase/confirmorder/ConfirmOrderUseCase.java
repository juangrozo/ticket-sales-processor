package co.com.ticketing.usecase.confirmorder;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * The customer confirms the purchase: tickets move to PENDING_CONFIRMATION and the payment is
 * queued. From here on the reservation no longer expires.
 */
@RequiredArgsConstructor
public class ConfirmOrderUseCase {

    private static final System.Logger LOG = System.getLogger(ConfirmOrderUseCase.class.getName());

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final StateChangeRepository stateChangeRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final Clock clock;

    public Mono<Order> requestConfirmation(String orderId, String customerId) {
        return orderRepository.findById(orderId)
                // Another customer's order answers like a missing one: its existence is not disclosed
                .filter(order -> order.customerId().equals(customerId))
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("Order", orderId)))
                .flatMap(order -> switch (order.status()) {
                    // A repeated confirmation returns the current state instead of failing
                    case CONFIRMING, CONFIRMED -> {
                        LOG.log(System.Logger.Level.INFO, "Order {0} already {1}, repeated confirmation ignored",
                                order.orderId(), order.status());
                        yield Mono.just(order);
                    }
                    default -> confirm(order);
                });
    }

    private Mono<Order> confirm(Order order) {
        return Mono.fromCallable(() -> order.apply(new RequestConfirmation(clock.instant())))
                .flatMap(confirming -> ticketRepository.findByOrderId(order.orderId())
                        .collectList()
                        .flatMap(tickets -> stateChangeRepository.apply(StateChange.transition(order, confirming, tickets,
                                TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)))
                        .doOnSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                                "Order {0} confirmation requested: AWAITING_CONFIRMATION -> CONFIRMING, tickets PENDING_CONFIRMATION",
                                order.orderId()))
                        .then(orderEventPublisher.publishConfirmationRequested(order.orderId())
                                // Stored as CONFIRMING already; the reconciliation job re-queues it
                                .onErrorResume(error -> {
                                    LOG.log(System.Logger.Level.WARNING, "Order {0} confirmed but not queued: {1}",
                                            order.orderId(), error.getMessage());
                                    return Mono.empty();
                                }))
                        .thenReturn(confirming));
    }
}
