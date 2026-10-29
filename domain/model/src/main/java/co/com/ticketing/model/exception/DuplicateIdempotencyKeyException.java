package co.com.ticketing.model.exception;

/**
 * Two requests with the same idempotency key raced and the other one stored its record first.
 * The use case resolves it by replaying the stored record.
 */
public final class DuplicateIdempotencyKeyException extends DomainException {

    public DuplicateIdempotencyKeyException(String key) {
        super("Idempotency key %s is already registered".formatted(key));
    }
}
