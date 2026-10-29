package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.idempotency.IdempotencyRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static co.com.ticketing.dynamodb.mapper.Attributes.n;
import static co.com.ticketing.dynamodb.mapper.Attributes.number;
import static co.com.ticketing.dynamodb.mapper.Attributes.s;
import static co.com.ticketing.dynamodb.mapper.Attributes.string;

public final class IdempotencyItemMapper {

    public static final String KEY = "idempotencyKey";

    private IdempotencyItemMapper() {
    }

    public static Map<String, AttributeValue> key(String scopedKey) {
        return Map.of(KEY, s(scopedKey));
    }

    /** {@code expiresAt} is in epoch seconds, the format DynamoDB TTL requires. */
    public static Map<String, AttributeValue> toItem(IdempotencyRecord record) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(record.key()));
        item.put("requestHash", s(record.requestHash()));
        item.put("orderId", s(record.orderId()));
        item.put("expiresAt", n(record.expiresAt().getEpochSecond()));
        return item;
    }

    public static IdempotencyRecord fromItem(Map<String, AttributeValue> item) {
        return new IdempotencyRecord(string(item, KEY), string(item, "requestHash"), string(item, "orderId"),
                Instant.ofEpochSecond(number(item, "expiresAt")));
    }
}
