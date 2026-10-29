package co.com.ticketing.model.exception;

/** The idempotency key was already used for a request with a different payload. */
public final class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request");
    }
}
