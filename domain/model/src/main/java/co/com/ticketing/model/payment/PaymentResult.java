package co.com.ticketing.model.payment;

/** Outcome of charging an order. Sealed so callers handle both outcomes exhaustively. */
public sealed interface PaymentResult {

    record Approved(String reference) implements PaymentResult {
    }

    record Declined(String reason) implements PaymentResult {
    }
}
