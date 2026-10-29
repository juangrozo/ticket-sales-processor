package co.com.ticketing.model.exception;

public final class DomainValidationException extends DomainException {

    public DomainValidationException(String message) {
        super(message);
    }
}
