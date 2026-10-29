package co.com.ticketing.model.order;

public enum OrderType {
    PURCHASE,
    /** Granted by an administrator; created already CONFIRMED and excluded from accounting. */
    COMPLIMENTARY
}
