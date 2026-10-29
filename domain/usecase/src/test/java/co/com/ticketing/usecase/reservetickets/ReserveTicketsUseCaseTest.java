package co.com.ticketing.usecase.reservetickets;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReserveTicketsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order PENDING = Order.place("o-1", "e-1", "c-1", 3, NOW.minusSeconds(5));

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private StateChangeRepository stateChangeRepository;

    private ReserveTicketsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReserveTicketsUseCase(orderRepository, stateChangeRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReserveOneTicketPerUnit() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();

        ArgumentCaptor<StateChange> captor = ArgumentCaptor.forClass(StateChange.class);
        verify(stateChangeRepository).apply(captor.capture());
        StateChange change = captor.getValue();
        assertThat(change.orderAfter().status()).isEqualTo(OrderStatus.AWAITING_CONFIRMATION);
        assertThat(change.orderAfter().expiresAt()).isEqualTo(NOW.plus(Order.RESERVATION_TTL));
        assertThat(change.inventoryMove().quantity()).isEqualTo(3);
        assertThat(change.createdTickets()).hasSize(3)
                .extracting(Ticket::status).containsOnly(TicketStatus.RESERVED);
        assertThat(change.createdTickets()).extracting(Ticket::ticketId).doesNotHaveDuplicates();
    }

    @Test
    void shouldRejectOrderWhenInventoryIsInsufficient() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING));
        when(stateChangeRepository.apply(any()))
                .thenReturn(Mono.error(new InsufficientInventoryException("e-1", 3)))
                .thenReturn(Mono.empty());

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();

        ArgumentCaptor<StateChange> captor = ArgumentCaptor.forClass(StateChange.class);
        verify(stateChangeRepository, times(2)).apply(captor.capture());
        StateChange rejection = captor.getAllValues().get(1);
        assertThat(rejection.orderAfter().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejection.orderAfter().failureReason()).isEqualTo(ReserveTicketsUseCase.INSUFFICIENT_INVENTORY);
        assertThat(rejection.inventoryMove()).isNull();
    }

    @Test
    void shouldIgnoreDuplicateMessageThatLostTheRace() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.error(new ConcurrentUpdateException("Order", "o-1")));

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();
    }

    @Test
    void shouldIgnoreAlreadyProcessedOrder() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING.apply(new Reserve(NOW))));

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();

        verify(stateChangeRepository, never()).apply(any());
    }

    @Test
    void shouldIgnoreUnknownOrder() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();

        verify(stateChangeRepository, never()).apply(any());
    }

    @Test
    void shouldPropagateUnexpectedErrorsSoTheMessageIsRetried() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(useCase.reserve("o-1")).expectError(IllegalStateException.class).verify();
    }

    @Test
    void reservationShouldContainOnlyThisOrdersTickets() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(PENDING));
        when(stateChangeRepository.apply(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.reserve("o-1")).verifyComplete();

        ArgumentCaptor<StateChange> captor = ArgumentCaptor.forClass(StateChange.class);
        verify(stateChangeRepository).apply(captor.capture());
        List<Ticket> tickets = captor.getValue().createdTickets();
        assertThat(tickets).extracting(Ticket::orderId).containsOnly("o-1");
        assertThat(tickets).extracting(Ticket::eventId).containsOnly("e-1");
    }
}
