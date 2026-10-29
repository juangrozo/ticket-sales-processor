package co.com.ticketing.model.order;

import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.order.OrderTransition.Confirm;
import co.com.ticketing.model.order.OrderTransition.Expire;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable order. Each transition returns a new instance with {@code version + 1}; persistence
 * writes it only if the stored version still matches (optimistic locking).
 *
 * @param expiresAt     end of the reservation; null until the tickets are reserved
 * @param failureReason why the order was rejected; null otherwise
 */
public record Order(String orderId, String eventId, String customerId, int quantity, OrderType type,
                    OrderStatus status, Instant createdAt, Instant statusChangedAt, Instant expiresAt,
                    String failureReason, long version) {

    public static final int MAX_TICKETS_PER_ORDER = 10;
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

    public Order {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        if (customerId == null || customerId.isBlank()) {
            throw new DomainValidationException("Customer id is required");
        }
        if (quantity < 1 || quantity > MAX_TICKETS_PER_ORDER) {
            throw new DomainValidationException("Quantity must be between 1 and " + MAX_TICKETS_PER_ORDER);
        }
    }

    public static Order place(String orderId, String eventId, String customerId, int quantity, Instant now) {
        return new Order(orderId, eventId, customerId, quantity, OrderType.PURCHASE,
                OrderStatus.PENDING, now, now, null, null, 0);
    }

    public static Order complimentary(String orderId, String eventId, String customerId, int quantity, Instant now) {
        return new Order(orderId, eventId, customerId, quantity, OrderType.COMPLIMENTARY,
                OrderStatus.CONFIRMED, now, now, null, null, 0);
    }

    public Order apply(OrderTransition transition) {
        return switch (transition) {
            case Reserve r when status == OrderStatus.PENDING ->
                    moveTo(OrderStatus.AWAITING_CONFIRMATION, r.at(), r.at().plus(RESERVATION_TTL), null);
            case RequestConfirmation c when status == OrderStatus.AWAITING_CONFIRMATION && c.at().isBefore(expiresAt) ->
                    moveTo(OrderStatus.CONFIRMING, c.at(), expiresAt, null);
            case RequestConfirmation _ when status == OrderStatus.AWAITING_CONFIRMATION ->
                    throw new ReservationExpiredException(orderId);
            case Confirm c when status == OrderStatus.CONFIRMING ->
                    moveTo(OrderStatus.CONFIRMED, c.at(), expiresAt, null);
            case Reject r when status == OrderStatus.PENDING || status == OrderStatus.CONFIRMING ->
                    moveTo(OrderStatus.REJECTED, r.at(), expiresAt, r.reason());
            // PENDING_CONFIRMATION tickets are never expired: payment is in progress
            case Expire e when status == OrderStatus.AWAITING_CONFIRMATION && !e.at().isBefore(expiresAt) ->
                    moveTo(OrderStatus.EXPIRED, e.at(), expiresAt, null);
            case Reserve _, RequestConfirmation _, Confirm _, Reject _, Expire _ ->
                    throw new InvalidStateTransitionException("Order", orderId, status.name(), targetOf(transition).name());
        };
    }

    public boolean isReservationExpired(Instant now) {
        return status == OrderStatus.AWAITING_CONFIRMATION && !now.isBefore(expiresAt);
    }

    private Order moveTo(OrderStatus target, Instant at, Instant newExpiresAt, String reason) {
        return new Order(orderId, eventId, customerId, quantity, type, target, createdAt, at,
                newExpiresAt, reason, version + 1);
    }

    private static OrderStatus targetOf(OrderTransition transition) {
        return switch (transition) {
            case Reserve _ -> OrderStatus.AWAITING_CONFIRMATION;
            case RequestConfirmation _ -> OrderStatus.CONFIRMING;
            case Confirm _ -> OrderStatus.CONFIRMED;
            case Reject _ -> OrderStatus.REJECTED;
            case Expire _ -> OrderStatus.EXPIRED;
        };
    }
}
