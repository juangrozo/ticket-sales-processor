package co.com.ticketing.model.ticket;

import co.com.ticketing.model.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void shouldCreateReservedTicket() {
        Ticket ticket = Ticket.reserved("t-1", "o-1", "e-1", NOW);

        assertThat(ticket.status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(ticket.statusChangedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldCreateComplimentaryTicket() {
        assertThat(Ticket.complimentary("t-1", "o-1", "e-1", NOW).status()).isEqualTo(TicketStatus.COMPLIMENTARY);
    }

    @Test
    void shouldFollowPurchaseLifecycle() {
        Instant later = NOW.plusSeconds(60);

        Ticket sold = Ticket.reserved("t-1", "o-1", "e-1", NOW)
                .transitionTo(TicketStatus.PENDING_CONFIRMATION, later)
                .transitionTo(TicketStatus.SOLD, later);

        assertThat(sold.status()).isEqualTo(TicketStatus.SOLD);
        assertThat(sold.statusChangedAt()).isEqualTo(later);
        assertThat(sold.ticketId()).isEqualTo("t-1");
    }

    @Test
    void shouldRejectInvalidTransition() {
        Ticket sold = new Ticket("t-1", "o-1", "e-1", TicketStatus.SOLD, NOW);

        assertThatThrownBy(() -> sold.transitionTo(TicketStatus.AVAILABLE, NOW))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SOLD")
                .hasMessageContaining("AVAILABLE");
    }

    @Test
    void shouldRequireMandatoryFields() {
        assertThatThrownBy(() -> new Ticket(null, "o-1", "e-1", TicketStatus.RESERVED, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
