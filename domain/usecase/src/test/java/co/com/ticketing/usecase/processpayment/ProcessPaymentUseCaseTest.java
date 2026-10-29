package co.com.ticketing.usecase.processpayment;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.payment.PaymentResult.Approved;
import co.com.ticketing.model.payment.PaymentResult.Declined;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessPaymentUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order CONFIRMING = Order.place("o-1", "e-1", "c-1", 1, NOW)
            .apply(new Reserve(NOW)).apply(new RequestConfirmation(NOW.plusSeconds(30)));
    private static final Ticket PENDING_TICKET = Ticket.reserved("t-1", "o-1", "e-1", NOW)
            .transitionTo(TicketStatus.PENDING_CONFIRMATION, NOW.plusSeconds(30));

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private StateChangeRepository stateChangeRepository;
    @Mock
    private PaymentGateway paymentGateway;

    private ProcessPaymentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentUseCase(orderRepository, ticketRepository, stateChangeRepository, paymentGateway,
                Clock.fixed(NOW.plusSeconds(40), ZoneOffset.UTC));
    }

    private StateChange settledChange() {
        ArgumentCaptor<StateChange> captor = ArgumentCaptor.forClass(StateChange.class);
        verify(stateChangeRepository).apply(captor.capture());
        return captor.getValue();
    }

    private void confirmingOrder() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(CONFIRMING));
        when(ticketRepository.findByOrderId("o-1")).thenReturn(Flux.just(PENDING_TICKET));
    }

    @Test
    void approvedPaymentShouldSellTheTickets() {
        confirmingOrder();
        when(paymentGateway.charge(CONFIRMING)).thenReturn(Mono.just(new Approved("ref-1")));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.process("o-1")).verifyComplete();

        StateChange change = settledChange();
        assertThat(change.orderAfter().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(change.inventoryMove().to()).isEqualTo(TicketStatus.SOLD);
        assertThat(change.changedTickets().getFirst().after().status()).isEqualTo(TicketStatus.SOLD);
    }

    @Test
    void declinedPaymentShouldReleaseTheTickets() {
        confirmingOrder();
        when(paymentGateway.charge(CONFIRMING)).thenReturn(Mono.just(new Declined("insufficient funds")));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.process("o-1")).verifyComplete();

        StateChange change = settledChange();
        assertThat(change.orderAfter().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(change.orderAfter().failureReason()).isEqualTo("Payment declined: insufficient funds");
        assertThat(change.inventoryMove().to()).isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void duplicateMessageForSettledOrderShouldDoNothing() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(Order.complimentary("o-1", "e-1", "c-1", 1, NOW)));

        StepVerifier.create(useCase.process("o-1")).verifyComplete();

        verify(paymentGateway, never()).charge(any());
    }

    @Test
    void lostRaceShouldDoNothing() {
        confirmingOrder();
        when(paymentGateway.charge(CONFIRMING)).thenReturn(Mono.just(new Approved("ref-1")));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.error(new ConcurrentUpdateException("Order", "o-1")));

        StepVerifier.create(useCase.process("o-1")).verifyComplete();
    }

    @Test
    void gatewayFailureShouldPropagateSoTheMessageIsRetried() {
        confirmingOrder();
        when(paymentGateway.charge(CONFIRMING)).thenReturn(Mono.error(new IllegalStateException("gateway down")));

        StepVerifier.create(useCase.process("o-1")).expectError(IllegalStateException.class).verify();

        verify(stateChangeRepository, never()).apply(any());
    }
}
