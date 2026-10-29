package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;

import java.util.List;

/** An order together with its tickets; tickets do not exist until the order is reserved. */
public record OrderDetails(Order order, List<Ticket> tickets) {

    public OrderDetails {
        tickets = List.copyOf(tickets);
    }

    /** All tickets of an order move together, so they share one status; null before reservation. */
    public TicketStatus ticketStatus() {
        return tickets.isEmpty() ? null : tickets.getFirst().status();
    }
}
