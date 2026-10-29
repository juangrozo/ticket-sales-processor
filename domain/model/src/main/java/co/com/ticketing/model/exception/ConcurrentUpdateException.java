package co.com.ticketing.model.exception;

/** The entity changed since it was read: another request or message already applied a transition. */
public final class ConcurrentUpdateException extends DomainException {

    public ConcurrentUpdateException(String entity, String id) {
        super("%s %s was modified concurrently".formatted(entity, id));
    }
}
