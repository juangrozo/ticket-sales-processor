package co.com.ticketing.usecase.processpayment;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.Confirm;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.model.payment.PaymentResult.Approved;
import co.com.ticketing.model.payment.PaymentResult.Declined;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Consumes a confirmation message: charges the order and settles it. Approved tickets become SOLD
 * (final); declined ones go back to AVAILABLE. Duplicate messages find the order already settled.
 */
@RequiredArgsConstructor
public class ProcessPaymentUseCase {

    static final String PAYMENT_DECLINED = "Payment declined: ";

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final StateChangeRepository stateChangeRepository;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public Mono<Void> process(String orderId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.status() == OrderStatus.CONFIRMING)
                .flatMap(order -> ticketRepository.findByOrderId(orderId)
                        .collectList()
                        .flatMap(tickets -> paymentGateway.charge(order)
                                .flatMap(result -> stateChangeRepository.apply(settle(order, tickets, result)))))
                .onErrorResume(ConcurrentUpdateException.class, _ -> Mono.empty());
    }

    private StateChange settle(Order order, List<Ticket> tickets, PaymentResult result) {
        Instant now = clock.instant();
        return switch (result) {
            case Approved _ -> StateChange.transition(order, order.apply(new Confirm(now)), tickets,
                    TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD);
            case Declined declined -> StateChange.transition(order,
                    order.apply(new Reject(now, PAYMENT_DECLINED + declined.reason())), tickets,
                    TicketStatus.PENDING_CONFIRMATION, TicketStatus.AVAILABLE);
        };
    }
}
