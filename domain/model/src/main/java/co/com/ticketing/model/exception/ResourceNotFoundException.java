package co.com.ticketing.model.exception;

public final class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, String id) {
        super("%s %s not found".formatted(resource, id));
    }
}
