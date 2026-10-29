package co.com.ticketing.model.exception;

public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String entity, String id, String from, String to) {
        super("%s %s cannot move from %s to %s".formatted(entity, id, from, to));
    }
}
