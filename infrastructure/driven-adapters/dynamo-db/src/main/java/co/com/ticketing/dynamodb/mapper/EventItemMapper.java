package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.ticket.TicketStatus;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static co.com.ticketing.dynamodb.mapper.Attributes.instant;
import static co.com.ticketing.dynamodb.mapper.Attributes.integer;
import static co.com.ticketing.dynamodb.mapper.Attributes.millis;
import static co.com.ticketing.dynamodb.mapper.Attributes.n;
import static co.com.ticketing.dynamodb.mapper.Attributes.s;
import static co.com.ticketing.dynamodb.mapper.Attributes.string;

public final class EventItemMapper {

    public static final String EVENT_ID = "eventId";

    private EventItemMapper() {
    }

    /** Name of the counter attribute that holds the tickets in the given status. */
    public static String counterOf(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> "available";
            case RESERVED -> "reserved";
            case PENDING_CONFIRMATION -> "pendingConfirmation";
            case SOLD -> "sold";
            case COMPLIMENTARY -> "complimentary";
        };
    }

    public static Map<String, AttributeValue> key(String eventId) {
        return Map.of(EVENT_ID, s(eventId));
    }

    public static Map<String, AttributeValue> toItem(Event event) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(EVENT_ID, s(event.eventId()));
        item.put("name", s(event.name()));
        item.put("venue", s(event.venue()));
        item.put("startsAt", millis(event.startsAt()));
        item.put("capacity", n(event.capacity()));
        item.put("createdAt", millis(event.createdAt()));
        for (TicketStatus status : TicketStatus.values()) {
            item.put(counterOf(status), n(event.inventory().count(status)));
        }
        return item;
    }

    public static Event fromItem(Map<String, AttributeValue> item) {
        Inventory inventory = new Inventory(
                integer(item, counterOf(TicketStatus.AVAILABLE)),
                integer(item, counterOf(TicketStatus.RESERVED)),
                integer(item, counterOf(TicketStatus.PENDING_CONFIRMATION)),
                integer(item, counterOf(TicketStatus.SOLD)),
                integer(item, counterOf(TicketStatus.COMPLIMENTARY)));
        return new Event(string(item, EVENT_ID), string(item, "name"), string(item, "venue"),
                instant(item, "startsAt"), integer(item, "capacity"), inventory, instant(item, "createdAt"));
    }
}
