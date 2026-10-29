package co.com.ticketing.api;

import co.com.ticketing.api.dto.CreateEventRequest;
import co.com.ticketing.api.dto.EventResponse;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.ticketing.usecase.getevent.GetEventUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class EventHandler {

    private final CreateEventUseCase createEventUseCase;
    private final GetEventUseCase getEventUseCase;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(CreateEventRequest.class)
                .switchIfEmpty(Mono.error(() -> new DomainValidationException("Request body is required")))
                .flatMap(body -> {
                    if (body.capacity() == null) {
                        return Mono.error(new DomainValidationException("capacity is required"));
                    }
                    return createEventUseCase.create(body.name(), body.venue(), body.startsAt(), body.capacity());
                })
                .flatMap(event -> ServerResponse.created(URI.create("/events/" + event.eventId()))
                        .bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return getEventUseCase.get(request.pathVariable("eventId"))
                .flatMap(event -> ServerResponse.ok().bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return ServerResponse.ok().body(getEventUseCase.list().map(EventResponse::from), EventResponse.class);
    }
}
