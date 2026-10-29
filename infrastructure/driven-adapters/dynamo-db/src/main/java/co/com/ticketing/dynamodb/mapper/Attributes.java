package co.com.ticketing.dynamodb.mapper;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

/** Helpers to build and read DynamoDB attribute values. Instants are stored as epoch millis. */
final class Attributes {

    private Attributes() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue millis(Instant instant) {
        return n(instant.toEpochMilli());
    }

    static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    static void putIfPresent(Map<String, AttributeValue> item, String name, Instant value) {
        if (value != null) {
            item.put(name, millis(value));
        }
    }

    static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    static int integer(Map<String, AttributeValue> item, String name) {
        return Integer.parseInt(item.get(name).n());
    }

    static long number(Map<String, AttributeValue> item, String name) {
        return Long.parseLong(item.get(name).n());
    }

    static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }
}
