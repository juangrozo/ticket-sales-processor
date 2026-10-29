package co.com.ticketing.usecase.confirmorder;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmOrderUseCaseTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order RESERVED = Order.place("o-1", "e-1", "c-1", 2, RESERVED_AT).apply(new Reserve(RESERVED_AT));
    private static final Ticket TICKET_1 = Ticket.reserved("t-1", "o-1", "e-1", RESERVED_AT);
    private static final Ticket TICKET_2 = Ticket.reserved("t-2", "o-1", "e-1", RESERVED_AT);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private StateChangeRepository stateChangeRepository;
    @Mock
    private OrderEventPublisher orderEventPublisher;

    private ConfirmOrderUseCase useCaseAt(Instant now) {
        return new ConfirmOrderUseCase(orderRepository, ticketRepository, stateChangeRepository,
                orderEventPublisher, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void reservedOrderWithTickets() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(RESERVED));
        when(ticketRepository.findByOrderId("o-1")).thenReturn(Flux.just(TICKET_1, TICKET_2));
    }

    @Test
    void shouldMoveTicketsToPendingConfirmationAndQueuePayment() {
        reservedOrderWithTickets();
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());
        when(orderEventPublisher.publishConfirmationRequested("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCaseAt(RESERVED_AT.plusSeconds(60)).requestConfirmation("o-1", "c-1"))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMING))
                .verifyComplete();

        ArgumentCaptor<StateChange> captor = ArgumentCaptor.forClass(StateChange.class);
        verify(stateChangeRepository).apply(captor.capture());
        assertThat(captor.getValue().inventoryMove().to()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(captor.getValue().changedTickets()).hasSize(2);
        verify(orderEventPublisher).publishConfirmationRequested("o-1");
    }

    @Test
    void shouldStillAnswerWhenQueueFails() {
        reservedOrderWithTickets();
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());
        when(orderEventPublisher.publishConfirmationRequested("o-1")).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(useCaseAt(RESERVED_AT.plusSeconds(60)).requestConfirmation("o-1", "c-1"))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMING))
                .verifyComplete();
    }

    @Test
    void shouldRejectConfirmationAfterTheReservationExpired() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(RESERVED));

        StepVerifier.create(useCaseAt(RESERVED.expiresAt()).requestConfirmation("o-1", "c-1"))
                .expectError(ReservationExpiredException.class)
                .verify();

        verify(stateChangeRepository, never()).apply(any());
    }

    @Test
    void anotherCustomersOrderShouldLookMissing() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(RESERVED));

        StepVerifier.create(useCaseAt(RESERVED_AT).requestConfirmation("o-1", "intruder"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    void repeatedConfirmationShouldReturnCurrentState() {
        Order confirming = RESERVED.apply(new RequestConfirmation(RESERVED_AT.plusSeconds(10)));
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(confirming));

        StepVerifier.create(useCaseAt(RESERVED_AT.plusSeconds(20)).requestConfirmation("o-1", "c-1"))
                .expectNext(confirming)
                .verifyComplete();

        verify(orderEventPublisher, never()).publishConfirmationRequested(anyString());
    }

    @Test
    void pendingOrderCannotBeConfirmedYet() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(Order.place("o-1", "e-1", "c-1", 1, RESERVED_AT)));

        StepVerifier.create(useCaseAt(RESERVED_AT).requestConfirmation("o-1", "c-1"))
                .expectError(InvalidStateTransitionException.class)
                .verify();
    }

    @Test
    void missingOrderShouldBeNotFound() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCaseAt(RESERVED_AT).requestConfirmation("o-1", "c-1"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }
}
