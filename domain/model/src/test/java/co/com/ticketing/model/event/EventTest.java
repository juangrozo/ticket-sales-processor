package co.com.ticketing.model.event;

import co.com.ticketing.model.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static co.com.ticketing.model.ticket.TicketStatus.AVAILABLE;
import static co.com.ticketing.model.ticket.TicketStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-12-15T20:00:00Z");

    @Test
    void shouldCreateEventWithFullInventoryAvailable() {
        Event event = Event.create("e-1", "Rock Fest", "Movistar Arena", STARTS_AT, 500, NOW);

        assertThat(event.inventory()).isEqualTo(Inventory.initial(500));
        assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldMoveTickets() {
        Event event = Event.create("e-1", "Rock Fest", "Movistar Arena", STARTS_AT, 500, NOW)
                .moveTickets(AVAILABLE, RESERVED, 2);

        assertThat(event.inventory().available()).isEqualTo(498);
        assertThat(event.inventory().reserved()).isEqualTo(2);
        assertThat(event.capacity()).isEqualTo(500);
    }

    @Test
    void shouldRejectPastDate() {
        assertThatThrownBy(() -> Event.create("e-1", "Rock Fest", "Arena", NOW, 500, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("future");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRequireName(String name) {
        assertThatThrownBy(() -> Event.create("e-1", name, "Arena", STARTS_AT, 500, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRequireVenue(String venue) {
        assertThatThrownBy(() -> Event.create("e-1", "Rock Fest", venue, STARTS_AT, 500, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Event.MAX_CAPACITY + 1})
    void shouldRejectInvalidCapacity(int capacity) {
        assertThatThrownBy(() -> Event.create("e-1", "Rock Fest", "Arena", STARTS_AT, capacity, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void shouldRejectInventoryThatDoesNotMatchCapacity() {
        Inventory inventory = Inventory.initial(10);

        assertThatThrownBy(() -> new Event("e-1", "Rock Fest", "Arena", STARTS_AT, 20, inventory, NOW))
                .isInstanceOf(DomainValidationException.class);
    }
}
