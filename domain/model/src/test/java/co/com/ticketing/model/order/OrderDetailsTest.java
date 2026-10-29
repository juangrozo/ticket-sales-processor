package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDetailsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order ORDER = Order.place("o-1", "e-1", "c-1", 1, NOW);

    @Test
    void ticketStatusShouldBeNullBeforeReservation() {
        assertThat(new OrderDetails(ORDER, List.of()).ticketStatus()).isNull();
    }

    @Test
    void ticketStatusShouldComeFromTickets() {
        OrderDetails details = new OrderDetails(ORDER, List.of(Ticket.reserved("t-1", "o-1", "e-1", NOW)));

        assertThat(details.ticketStatus()).isEqualTo(TicketStatus.RESERVED);
    }
}
