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
 * @param orderBefore   null when the transition creates the order
 * @param inventoryMove null when the transition does not touch the event counters
 */
public record StateChange(Order orderBefore, Order orderAfter, InventoryMove inventoryMove,
                          List<Ticket> createdTickets, List<TicketChange> changedTickets) {

    public record InventoryMove(String eventId, TicketStatus from, TicketStatus to, int quantity) {
    }

    public record TicketChange(Ticket before, Ticket after) {
    }

    public StateChange {
        Objects.requireNonNull(orderAfter, "orderAfter");
        if (orderBefore != null && !orderBefore.orderId().equals(orderAfter.orderId())) {
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

    /** A complimentary grant: the order is created already final, with its tickets taken from AVAILABLE. */
    public static StateChange complimentary(Order order, List<Ticket> tickets) {
        return new StateChange(null, order,
                new InventoryMove(order.eventId(), TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, tickets.size()),
                tickets, List.of());
    }

    /**
     * Moves the order and all its tickets together, and the same number of tickets between the event
     * counters. Every ticket must currently be in {@code from}.
     */
    public static StateChange transition(Order before, Order after, List<Ticket> tickets,
                                         TicketStatus from, TicketStatus to) {
        List<TicketChange> changes = tickets.stream()
                .map(ticket -> {
                    if (ticket.status() != from) {
                        throw new DomainValidationException("Ticket %s is %s, expected %s"
                                .formatted(ticket.ticketId(), ticket.status(), from));
                    }
                    return new TicketChange(ticket, ticket.transitionTo(to, after.statusChangedAt()));
                })
                .toList();
        return new StateChange(before, after, new InventoryMove(before.eventId(), from, to, tickets.size()),
                List.of(), changes);
    }

    public static StateChange orderOnly(Order before, Order after) {
        return new StateChange(Objects.requireNonNull(before, "orderBefore"), after, null, List.of(), List.of());
    }

    public boolean createsOrder() {
        return orderBefore == null;
    }

    public List<AuditEntry> auditEntries() {
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(createsOrder() ? AuditEntry.orderCreated(orderAfter) : AuditEntry.orderChanged(orderBefore, orderAfter));
        createdTickets.forEach(ticket -> entries.add(AuditEntry.ticketCreated(ticket)));
        changedTickets.forEach(change -> entries.add(AuditEntry.ticketChanged(change.before(), change.after())));
        return entries;
    }
}
