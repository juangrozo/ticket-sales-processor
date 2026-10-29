package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.audit.AuditEntry;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static co.com.ticketing.dynamodb.mapper.Attributes.millis;
import static co.com.ticketing.dynamodb.mapper.Attributes.putIfPresent;
import static co.com.ticketing.dynamodb.mapper.Attributes.s;

public final class AuditItemMapper {

    private AuditItemMapper() {
    }

    /**
     * The sort key starts with the zero-padded timestamp so a Query returns an entity's history in
     * order; the random suffix keeps two changes in the same millisecond apart.
     */
    public static Map<String, AttributeValue> toItem(AuditEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("entityId", s(entry.entityId()));
        item.put("auditId", s("%013d#%s".formatted(entry.occurredAt().toEpochMilli(), UUID.randomUUID())));
        item.put("entityType", s(entry.entityType()));
        putIfPresent(item, "fromStatus", entry.fromStatus());
        item.put("toStatus", s(entry.toStatus()));
        item.put("occurredAt", millis(entry.occurredAt()));
        putIfPresent(item, "detail", entry.detail());
        return item;
    }
}
