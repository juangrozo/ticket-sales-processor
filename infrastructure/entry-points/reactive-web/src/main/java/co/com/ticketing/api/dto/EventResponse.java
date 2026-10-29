package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;

import java.time.Instant;

public record EventResponse(String eventId, String name, String venue, Instant startsAt, int capacity,
                            InventoryResponse inventory, Instant createdAt) {

    public record InventoryResponse(int available, int reserved, int pendingConfirmation, int sold, int complimentary) {

        static InventoryResponse from(Inventory inventory) {
            return new InventoryResponse(inventory.available(), inventory.reserved(), inventory.pendingConfirmation(),
                    inventory.sold(), inventory.complimentary());
        }
    }

    public static EventResponse from(Event event) {
        return new EventResponse(event.eventId(), event.name(), event.venue(), event.startsAt(), event.capacity(),
                InventoryResponse.from(event.inventory()), event.createdAt());
    }
}
