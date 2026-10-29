package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static co.com.ticketing.dynamodb.mapper.Attributes.instant;
import static co.com.ticketing.dynamodb.mapper.Attributes.millis;
import static co.com.ticketing.dynamodb.mapper.Attributes.s;
import static co.com.ticketing.dynamodb.mapper.Attributes.string;

public final class TicketItemMapper {

    public static final String ORDER_ID = "orderId";
    public static final String TICKET_ID = "ticketId";
    public static final String TICKET_STATUS = "ticketStatus";

    private TicketItemMapper() {
    }

    public static Map<String, AttributeValue> toItem(Ticket ticket) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORDER_ID, s(ticket.orderId()));
        item.put(TICKET_ID, s(ticket.ticketId()));
        item.put("eventId", s(ticket.eventId()));
        item.put(TICKET_STATUS, s(ticket.status().name()));
        item.put("statusChangedAt", millis(ticket.statusChangedAt()));
        return item;
    }

    public static Ticket fromItem(Map<String, AttributeValue> item) {
        return new Ticket(string(item, TICKET_ID), string(item, ORDER_ID), string(item, "eventId"),
                TicketStatus.valueOf(string(item, TICKET_STATUS)), instant(item, "statusChangedAt"));
    }
}
