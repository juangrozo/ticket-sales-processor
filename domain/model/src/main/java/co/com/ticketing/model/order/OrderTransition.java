package co.com.ticketing.model.order;

import java.time.Instant;

/**
 * Every change an order can go through. Sealed so {@link Order#apply(OrderTransition)} handles
 * all of them in an exhaustive switch.
 */
public sealed interface OrderTransition {

    Instant at();

    /** The consumer took the tickets from inventory. */
    record Reserve(Instant at) implements OrderTransition {
    }

    /** The customer confirmed and payment started. */
    record RequestConfirmation(Instant at) implements OrderTransition {
    }

    /** Payment succeeded. */
    record Confirm(Instant at) implements OrderTransition {
    }

    /** Not enough inventory, or payment failed. */
    record Reject(Instant at, String reason) implements OrderTransition {
    }

    /** The reservation reached its time limit without confirmation. */
    record Expire(Instant at) implements OrderTransition {
    }
}
