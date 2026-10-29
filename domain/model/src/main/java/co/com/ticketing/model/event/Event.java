package co.com.ticketing.model.event;

import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.Objects;

public record Event(String eventId, String name, String venue, Instant startsAt, int capacity,
                    Inventory inventory, Instant createdAt) {

    public static final int MAX_CAPACITY = 100_000;

    public Event {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(createdAt, "createdAt");
        if (name == null || name.isBlank()) {
            throw new DomainValidationException("Event name is required");
        }
        if (venue == null || venue.isBlank()) {
            throw new DomainValidationException("Event venue is required");
        }
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new DomainValidationException("Capacity must be between 1 and " + MAX_CAPACITY);
        }
        if (inventory.total() != capacity) {
            throw new DomainValidationException("Inventory must add up to the event capacity");
        }
    }

    public static Event create(String eventId, String name, String venue, Instant startsAt, int capacity, Instant now) {
        if (startsAt != null && !startsAt.isAfter(now)) {
            throw new DomainValidationException("Event date must be in the future");
        }
        return new Event(eventId, name, venue, startsAt, capacity, Inventory.initial(capacity), now);
    }

    public Event moveTickets(TicketStatus from, TicketStatus to, int quantity) {
        return new Event(eventId, name, venue, startsAt, capacity,
                inventory.move(eventId, from, to, quantity), createdAt);
    }
}
