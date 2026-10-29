package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static co.com.ticketing.dynamodb.mapper.Attributes.instant;
import static co.com.ticketing.dynamodb.mapper.Attributes.integer;
import static co.com.ticketing.dynamodb.mapper.Attributes.millis;
import static co.com.ticketing.dynamodb.mapper.Attributes.n;
import static co.com.ticketing.dynamodb.mapper.Attributes.number;
import static co.com.ticketing.dynamodb.mapper.Attributes.putIfPresent;
import static co.com.ticketing.dynamodb.mapper.Attributes.s;
import static co.com.ticketing.dynamodb.mapper.Attributes.string;

public final class OrderItemMapper {

    public static final String ORDER_ID = "orderId";
    public static final String ORDER_STATUS = "orderStatus";
    public static final String STATUS_CHANGED_AT = "statusChangedAt";
    public static final String VERSION = "version";

    private OrderItemMapper() {
    }

    public static Map<String, AttributeValue> key(String orderId) {
        return Map.of(ORDER_ID, s(orderId));
    }

    public static Map<String, AttributeValue> toItem(Order order) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORDER_ID, s(order.orderId()));
        item.put("eventId", s(order.eventId()));
        item.put("customerId", s(order.customerId()));
        item.put("quantity", n(order.quantity()));
        item.put("orderType", s(order.type().name()));
        item.put(ORDER_STATUS, s(order.status().name()));
        item.put("createdAt", millis(order.createdAt()));
        item.put(STATUS_CHANGED_AT, millis(order.statusChangedAt()));
        putIfPresent(item, "expiresAt", order.expiresAt());
        putIfPresent(item, "failureReason", order.failureReason());
        item.put(VERSION, n(order.version()));
        return item;
    }

    public static Order fromItem(Map<String, AttributeValue> item) {
        return new Order(string(item, ORDER_ID), string(item, "eventId"), string(item, "customerId"),
                integer(item, "quantity"), OrderType.valueOf(string(item, "orderType")),
                OrderStatus.valueOf(string(item, ORDER_STATUS)), instant(item, "createdAt"),
                instant(item, STATUS_CHANGED_AT), instant(item, "expiresAt"), string(item, "failureReason"),
                number(item, VERSION));
    }
}
