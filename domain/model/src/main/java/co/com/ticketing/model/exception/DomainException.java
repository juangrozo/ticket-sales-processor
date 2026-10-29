package co.com.ticketing.model.exception;

/**
 * Root of every business error. Sealed so entry points can map each error to a response
 * with an exhaustive switch: adding a new error type breaks the build until it is handled.
 */
public abstract sealed class DomainException extends RuntimeException
        permits DomainValidationException,
                ResourceNotFoundException,
                InvalidStateTransitionException,
                InsufficientInventoryException,
                ReservationExpiredException,
                ConcurrentUpdateException,
                IdempotencyConflictException,
                DuplicateIdempotencyKeyException {

    protected DomainException(String message) {
        super(message);
    }
}
