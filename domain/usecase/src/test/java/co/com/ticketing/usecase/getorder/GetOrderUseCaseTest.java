package co.com.ticketing.usecase.getorder;

import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order ORDER = Order.place("o-1", "e-1", "c-1", 1, NOW);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private GetOrderUseCase useCase;

    @Test
    void shouldReturnOrderWithTickets() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(ORDER));
        when(ticketRepository.findByOrderId("o-1")).thenReturn(Flux.just(Ticket.reserved("t-1", "o-1", "e-1", NOW)));

        StepVerifier.create(useCase.get("o-1"))
                .assertNext(details -> {
                    assertThat(details.order()).isEqualTo(ORDER);
                    assertThat(details.ticketStatus()).isEqualTo(TicketStatus.RESERVED);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWhenOrderDoesNotExist() {
        when(orderRepository.findById("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.get("o-1")).expectError(ResourceNotFoundException.class).verify();
    }
}
