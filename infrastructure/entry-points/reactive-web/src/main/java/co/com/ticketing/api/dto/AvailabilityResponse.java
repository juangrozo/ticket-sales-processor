package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;

/**
 * Current availability. {@code available} already discounts both sold tickets and tickets held
 * by temporary reservations.
 */
public record AvailabilityResponse(String eventId, int capacity, int available, int reserved,
                                   int pendingConfirmation, int sold, int complimentary) {

    public static AvailabilityResponse from(String eventId, int capacity, Inventory inventory) {
        return new AvailabilityResponse(eventId, capacity, inventory.available(), inventory.reserved(),
                inventory.pendingConfirmation(), inventory.sold(), inventory.complimentary());
    }

    public static AvailabilityResponse from(Event event) {
        return from(event.eventId(), event.capacity(), event.inventory());
    }
}
