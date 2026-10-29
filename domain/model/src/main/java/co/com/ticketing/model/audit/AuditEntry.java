package co.com.ticketing.model.audit;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.Ticket;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only record of a state change. Written in the same transaction as the change itself,
 * so a transition is never persisted without its audit trail.
 *
 * @param fromStatus null when the entity is created
 */
public record AuditEntry(String entityType, String entityId, String fromStatus, String toStatus,
                         Instant occurredAt, String detail) {

    public AuditEntry {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static AuditEntry orderCreated(Order order) {
        return new AuditEntry("ORDER", order.orderId(), null, order.status().name(), order.createdAt(), null);
    }

    public static AuditEntry orderChanged(Order before, Order after) {
        return new AuditEntry("ORDER", after.orderId(), before.status().name(), after.status().name(),
                after.statusChangedAt(), after.failureReason());
    }

    public static AuditEntry ticketCreated(Ticket ticket) {
        return new AuditEntry("TICKET", ticket.ticketId(), null, ticket.status().name(), ticket.statusChangedAt(), null);
    }

    public static AuditEntry ticketChanged(Ticket before, Ticket after) {
        return new AuditEntry("TICKET", after.ticketId(), before.status().name(), after.status().name(),
                after.statusChangedAt(), null);
    }
}
