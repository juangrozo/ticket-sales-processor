package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.audit.AuditEntry;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItemMappersTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00.123Z");

    @Test
    void eventShouldRoundTrip() {
        Event event = Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(3600), 100, NOW)
                .moveTickets(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 3);

        Map<String, AttributeValue> item = EventItemMapper.toItem(event);

        assertThat(item.get("available").n()).isEqualTo("97");
        assertThat(EventItemMapper.fromItem(item)).isEqualTo(event);
        assertThat(EventItemMapper.key("e-1")).containsEntry("eventId", AttributeValue.fromS("e-1"));
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void everyTicketStatusShouldHaveItsOwnCounter(TicketStatus status) {
        assertThat(EventItemMapper.counterOf(status)).isNotBlank();
    }

    @Test
    void pendingOrderShouldRoundTripWithoutOptionalFields() {
        Order order = Order.place("o-1", "e-1", "c-1", 2, NOW);

        Map<String, AttributeValue> item = OrderItemMapper.toItem(order);

        assertThat(item).doesNotContainKeys("expiresAt", "failureReason");
        assertThat(item.get("statusChangedAt").n()).isEqualTo(Long.toString(NOW.toEpochMilli()));
        assertThat(OrderItemMapper.fromItem(item)).isEqualTo(order);
    }

    @Test
    void reservedAndRejectedOrdersShouldRoundTrip() {
        Order reserved = Order.place("o-1", "e-1", "c-1", 2, NOW).apply(new Reserve(NOW));
        Order rejected = Order.place("o-2", "e-1", "c-1", 2, NOW).apply(new Reject(NOW, "Insufficient inventory"));

        assertThat(OrderItemMapper.fromItem(OrderItemMapper.toItem(reserved))).isEqualTo(reserved);
        assertThat(OrderItemMapper.fromItem(OrderItemMapper.toItem(rejected))).isEqualTo(rejected);
        assertThat(OrderItemMapper.key("o-1")).containsEntry("orderId", AttributeValue.fromS("o-1"));
    }

    @Test
    void ticketShouldRoundTrip() {
        Ticket ticket = Ticket.reserved("t-1", "o-1", "e-1", NOW);

        assertThat(TicketItemMapper.fromItem(TicketItemMapper.toItem(ticket))).isEqualTo(ticket);
    }

    @Test
    void idempotencyRecordShouldStoreTtlInEpochSeconds() {
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", Instant.parse("2026-11-01T10:00:00Z"));

        Map<String, AttributeValue> item = IdempotencyItemMapper.toItem(record);

        assertThat(item.get("expiresAt").n()).isEqualTo(Long.toString(record.expiresAt().getEpochSecond()));
        assertThat(IdempotencyItemMapper.fromItem(item)).isEqualTo(record);
        assertThat(IdempotencyItemMapper.key("c-1#k")).containsEntry("idempotencyKey", AttributeValue.fromS("c-1#k"));
    }

    @Test
    void auditItemShouldBeSortableByTime() {
        AuditEntry created = new AuditEntry("ORDER", "o-1", null, "PENDING", NOW, null);
        AuditEntry changed = new AuditEntry("ORDER", "o-1", "PENDING", "REJECTED", NOW.plusMillis(1), "no stock");

        Map<String, AttributeValue> first = AuditItemMapper.toItem(created);
        Map<String, AttributeValue> second = AuditItemMapper.toItem(changed);

        assertThat(first).doesNotContainKeys("fromStatus", "detail");
        assertThat(second.get("detail").s()).isEqualTo("no stock");
        assertThat(first.get("auditId").s()).startsWith("%013d#".formatted(NOW.toEpochMilli()));
        assertThat(first.get("auditId").s()).isLessThan(second.get("auditId").s());
    }
}
