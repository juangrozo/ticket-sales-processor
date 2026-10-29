package co.com.ticketing.model.change;

import co.com.ticketing.model.audit.AuditEntry;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything one business transition changes, persisted all-or-nothing (Unit of Work).
 * The audit trail is derived from the change itself, so no transition can be stored without it.
 *
 * @param inventoryMove null when the transition does not touch the event counters
 */
public record StateChange(Order orderBefore, Order orderAfter, InventoryMove inventoryMove,
                          List<Ticket> createdTickets, List<TicketChange> changedTickets) {

    public record InventoryMove(String eventId, TicketStatus from, TicketStatus to, int quantity) {
    }

    public record TicketChange(Ticket before, Ticket after) {
    }

    public StateChange {
        Objects.requireNonNull(orderBefore, "orderBefore");
        Objects.requireNonNull(orderAfter, "orderAfter");
        if (!orderBefore.orderId().equals(orderAfter.orderId())) {
            throw new DomainValidationException("A state change must apply to a single order");
        }
        createdTickets = List.copyOf(createdTickets);
        changedTickets = List.copyOf(changedTickets);
    }

    public static StateChange reservation(Order before, Order after, List<Ticket> tickets) {
        return new StateChange(before, after,
                new InventoryMove(before.eventId(), TicketStatus.AVAILABLE, TicketStatus.RESERVED, tickets.size()),
                tickets, List.of());
    }

    public static StateChange orderOnly(Order before, Order after) {
        return new StateChange(before, after, null, List.of(), List.of());
    }

    public List<AuditEntry> auditEntries() {
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(AuditEntry.orderChanged(orderBefore, orderAfter));
        createdTickets.forEach(ticket -> entries.add(AuditEntry.ticketCreated(ticket)));
        changedTickets.forEach(change -> entries.add(AuditEntry.ticketChanged(change.before(), change.after())));
        return entries;
    }
}
