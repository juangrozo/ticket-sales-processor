package co.com.ticketing.model.ticket;

import co.com.ticketing.model.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.util.Objects;

/**
 * A single ticket of an order. Created when inventory is taken (reservation or complimentary
 * grant), never pre-generated: AVAILABLE tickets live only as the event counter.
 */
public record Ticket(String ticketId, String orderId, String eventId, TicketStatus status, Instant statusChangedAt) {

    public Ticket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
    }

    public static Ticket reserved(String ticketId, String orderId, String eventId, Instant now) {
        return new Ticket(ticketId, orderId, eventId, TicketStatus.RESERVED, now);
    }

    public static Ticket complimentary(String ticketId, String orderId, String eventId, Instant now) {
        return new Ticket(ticketId, orderId, eventId, TicketStatus.COMPLIMENTARY, now);
    }

    public Ticket transitionTo(TicketStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("Ticket", ticketId, status.name(), target.name());
        }
        return new Ticket(ticketId, orderId, eventId, target, now);
    }
}
