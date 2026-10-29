package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;

/**
 * The same counters seen by three kinds of report. Complimentary tickets leave the inventory like
 * a sale but never count in accounting.
 */
public record EventReportResponse(String eventId, String name, int capacity, Operational operational,
                                  Accounting accounting, InventoryView inventory) {

    /** What can still be sold and what is held by customers who have not paid yet. */
    public record Operational(int available, int reserved, int pendingConfirmation) {
    }

    /** Sales only. */
    public record Accounting(int soldTickets) {
    }

    /** Where every ticket of the capacity is. */
    public record InventoryView(int total, int available, int onHold, int sold, int complimentary) {
    }

    public static EventReportResponse from(Event event) {
        Inventory inventory = event.inventory();
        return new EventReportResponse(event.eventId(), event.name(), event.capacity(),
                new Operational(inventory.available(), inventory.reserved(), inventory.pendingConfirmation()),
                new Accounting(inventory.accountable()),
                new InventoryView(inventory.total(), inventory.available(), inventory.onHold(), inventory.sold(),
                        inventory.complimentary()));
    }
}
