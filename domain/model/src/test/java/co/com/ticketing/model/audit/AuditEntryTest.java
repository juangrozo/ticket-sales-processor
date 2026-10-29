package co.com.ticketing.model.audit;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEntryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void shouldAuditOrderCreation() {
        AuditEntry entry = AuditEntry.orderCreated(Order.place("o-1", "e-1", "c-1", 1, NOW));

        assertThat(entry).isEqualTo(new AuditEntry("ORDER", "o-1", null, "PENDING", NOW, null));
    }

    @Test
    void shouldAuditOrderChangeWithReason() {
        Order before = Order.place("o-1", "e-1", "c-1", 1, NOW);
        Order after = before.apply(new Reject(NOW.plusSeconds(1), "Insufficient inventory"));

        AuditEntry entry = AuditEntry.orderChanged(before, after);

        assertThat(entry.fromStatus()).isEqualTo("PENDING");
        assertThat(entry.toStatus()).isEqualTo("REJECTED");
        assertThat(entry.occurredAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(entry.detail()).isEqualTo("Insufficient inventory");
    }

    @Test
    void shouldAuditTicketLifecycle() {
        Ticket reserved = Ticket.reserved("t-1", "o-1", "e-1", NOW);
        Ticket pending = reserved.transitionTo(TicketStatus.PENDING_CONFIRMATION, NOW.plusSeconds(5));

        assertThat(AuditEntry.ticketCreated(reserved))
                .isEqualTo(new AuditEntry("TICKET", "t-1", null, "RESERVED", NOW, null));
        assertThat(AuditEntry.ticketChanged(reserved, pending))
                .isEqualTo(new AuditEntry("TICKET", "t-1", "RESERVED", "PENDING_CONFIRMATION", NOW.plusSeconds(5), null));
    }

    @Test
    void shouldRequireTargetStatus() {
        assertThatThrownBy(() -> new AuditEntry("ORDER", "o-1", null, null, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }
}
