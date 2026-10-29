package co.com.ticketing.model.event;

import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;

/**
 * Ticket counters of an event, one per {@link TicketStatus}. Persistence applies the same
 * moves as atomic conditional updates; this type keeps the rules in the domain and backs
 * the in-memory implementations used in tests.
 */
public record Inventory(int available, int reserved, int pendingConfirmation, int sold, int complimentary) {

    public Inventory {
        if (available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new DomainValidationException("Inventory counters cannot be negative");
        }
    }

    public static Inventory initial(int capacity) {
        return new Inventory(capacity, 0, 0, 0, 0);
    }

    public int total() {
        return available + reserved + pendingConfirmation + sold + complimentary;
    }

    public int count(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> available;
            case RESERVED -> reserved;
            case PENDING_CONFIRMATION -> pendingConfirmation;
            case SOLD -> sold;
            case COMPLIMENTARY -> complimentary;
        };
    }

    /** Moves {@code quantity} tickets between states, enforcing the ticket state machine. */
    public Inventory move(String eventId, TicketStatus from, TicketStatus to, int quantity) {
        if (quantity <= 0) {
            throw new DomainValidationException("Quantity must be positive");
        }
        if (!from.canTransitionTo(to)) {
            throw new InvalidStateTransitionException("Inventory of event", eventId, from.name(), to.name());
        }
        if (count(from) < quantity) {
            throw new InsufficientInventoryException(eventId, quantity, count(from));
        }
        return with(from, count(from) - quantity).with(to, count(to) + quantity);
    }

    private Inventory with(TicketStatus status, int value) {
        return switch (status) {
            case AVAILABLE -> new Inventory(value, reserved, pendingConfirmation, sold, complimentary);
            case RESERVED -> new Inventory(available, value, pendingConfirmation, sold, complimentary);
            case PENDING_CONFIRMATION -> new Inventory(available, reserved, value, sold, complimentary);
            case SOLD -> new Inventory(available, reserved, pendingConfirmation, value, complimentary);
            case COMPLIMENTARY -> new Inventory(available, reserved, pendingConfirmation, sold, value);
        };
    }
}
