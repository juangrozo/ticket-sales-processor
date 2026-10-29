package co.com.ticketing.model.ticket;

/**
 * Lifecycle of a single ticket. SOLD and COMPLIMENTARY are final; RESERVED and
 * PENDING_CONFIRMATION hold inventory but are not sales.
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            // Back to AVAILABLE when the reservation expires or the payment fails
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    /** Only SOLD tickets count for accounting reports; COMPLIMENTARY is final but not accountable. */
    public boolean isAccountable() {
        return this == SOLD;
    }
}
