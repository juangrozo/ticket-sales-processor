package co.com.ticketing.model.exception;

public final class InsufficientInventoryException extends DomainException {

    public InsufficientInventoryException(String eventId, int requested, int available) {
        super("Event %s has %d tickets in the requested state, %d requested".formatted(eventId, available, requested));
    }
}
