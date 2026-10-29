package co.com.ticketing.usecase.assigncomplimentary;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.usecase.support.InMemoryTicketingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AssignComplimentaryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private InMemoryTicketingStore store;
    private AssignComplimentaryUseCase useCase;

    @BeforeEach
    void setUp() {
        store = new InMemoryTicketingStore();
        useCase = new AssignComplimentaryUseCase(store.eventRepository(), store.stateChangeRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        store.eventRepository().save(Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(3600), 5, NOW)).block();
    }

    @Test
    void shouldGrantFinalNonAccountableTickets() {
        StepVerifier.create(useCase.assign("e-1", "vip-1", 2))
                .assertNext(order -> {
                    assertThat(order.type()).isEqualTo(OrderType.COMPLIMENTARY);
                    assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
                })
                .verifyComplete();

        var inventory = store.event("e-1").inventory();
        assertThat(inventory.complimentary()).isEqualTo(2);
        assertThat(inventory.available()).isEqualTo(3);
        assertThat(inventory.accountable()).isZero();
        assertThat(store.ticketCount()).isEqualTo(2);
        assertThat(store.audit()).hasSize(3);
    }

    @Test
    void shouldNotGrantMoreThanAvailable() {
        StepVerifier.create(useCase.assign("e-1", "vip-1", Order.MAX_TICKETS_PER_ORDER + 1))
                .expectError(DomainValidationException.class)
                .verify();
        StepVerifier.create(useCase.assign("e-1", "vip-1", 6))
                .expectError(InsufficientInventoryException.class)
                .verify();
        useCase.assign("e-1", "vip-1", 4).block();

        StepVerifier.create(useCase.assign("e-1", "vip-2", 2))
                .expectError(InsufficientInventoryException.class)
                .verify();
        assertThat(store.event("e-1").inventory().total()).isEqualTo(5);
    }

    @Test
    void unknownEventShouldBeNotFound() {
        StepVerifier.create(useCase.assign("e-9", "vip-1", 1))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }
}
