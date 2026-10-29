package co.com.ticketing.model.order;

/**
 * Lifecycle of an order, separate from the ticket lifecycle: an order is queued and processed
 * before any ticket exists.
 */
public enum OrderStatus {
    PENDING,
    AWAITING_CONFIRMATION,
    CONFIRMING,
    CONFIRMED,
    REJECTED,
    EXPIRED;

    public boolean isFinal() {
        return this == CONFIRMED || this == REJECTED || this == EXPIRED;
    }
}
