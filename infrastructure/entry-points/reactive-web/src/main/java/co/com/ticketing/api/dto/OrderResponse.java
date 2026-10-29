package co.com.ticketing.api.dto;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderDetails;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.List;

/**
 * Exposes both state machines: {@code orderStatus} for the order and {@code ticketStatus} for its
 * tickets (null until the order is reserved).
 */
public record OrderResponse(String orderId, String eventId, String customerId, int quantity, OrderType type,
                            OrderStatus orderStatus, TicketStatus ticketStatus, Instant createdAt,
                            Instant expiresAt, String failureReason, List<TicketResponse> tickets) {

    public record TicketResponse(String ticketId, TicketStatus status) {

        static TicketResponse from(Ticket ticket) {
            return new TicketResponse(ticket.ticketId(), ticket.status());
        }
    }

    public static OrderResponse from(Order order) {
        return from(new OrderDetails(order, List.of()));
    }

    public static OrderResponse from(OrderDetails details) {
        Order order = details.order();
        return new OrderResponse(order.orderId(), order.eventId(), order.customerId(), order.quantity(), order.type(),
                order.status(), details.ticketStatus(), order.createdAt(), order.expiresAt(), order.failureReason(),
                details.tickets().stream().map(TicketResponse::from).toList());
    }
}
