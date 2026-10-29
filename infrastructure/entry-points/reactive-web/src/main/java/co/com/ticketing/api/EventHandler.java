package co.com.ticketing.api;

import co.com.ticketing.api.config.ApiProperties;
import co.com.ticketing.api.dto.AssignComplimentaryRequest;
import co.com.ticketing.api.dto.AvailabilityResponse;
import co.com.ticketing.api.dto.CreateEventRequest;
import co.com.ticketing.api.dto.EventReportResponse;
import co.com.ticketing.api.dto.EventResponse;
import co.com.ticketing.api.dto.OrderResponse;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.usecase.assigncomplimentary.AssignComplimentaryUseCase;
import co.com.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.ticketing.usecase.getevent.GetEventUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class EventHandler {

    private final CreateEventUseCase createEventUseCase;
    private final GetEventUseCase getEventUseCase;
    private final AssignComplimentaryUseCase assignComplimentaryUseCase;
    private final AdminAuthorizer adminAuthorizer;
    private final ApiProperties apiProperties;

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

    public Mono<ServerResponse> availability(ServerRequest request) {
        return getEventUseCase.get(request.pathVariable("eventId"))
                .flatMap(event -> ServerResponse.ok().bodyValue(AvailabilityResponse.from(event)));
    }

    /** Server-Sent Events: one event now, then one each time the availability changes. */
    public Mono<ServerResponse> availabilityStream(ServerRequest request) {
        String eventId = request.pathVariable("eventId");
        return getEventUseCase.get(eventId)
                .flatMap(event -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(BodyInserters.fromServerSentEvents(getEventUseCase
                                .watchInventory(eventId, apiProperties.availabilityPollInterval(),
                                        apiProperties.availabilityMaxDuration())
                                .map(inventory -> ServerSentEvent.builder(
                                                AvailabilityResponse.from(eventId, event.capacity(), inventory))
                                        .event("availability")
                                        .build()))));
    }

    public Mono<ServerResponse> report(ServerRequest request) {
        return getEventUseCase.get(request.pathVariable("eventId"))
                .flatMap(event -> ServerResponse.ok().bodyValue(EventReportResponse.from(event)));
    }

    public Mono<ServerResponse> assignComplimentary(ServerRequest request) {
        String eventId = request.pathVariable("eventId");
        return Mono.fromRunnable(() -> adminAuthorizer.authorize(request))
                .then(request.bodyToMono(AssignComplimentaryRequest.class))
                .switchIfEmpty(Mono.error(() -> new DomainValidationException("Request body is required")))
                .flatMap(body -> {
                    if (body.quantity() == null) {
                        return Mono.error(new DomainValidationException("quantity is required"));
                    }
                    return assignComplimentaryUseCase.assign(eventId, body.recipientId(), body.quantity());
                })
                .flatMap(grant -> ServerResponse.created(URI.create("/orders/" + grant.orderId()))
                        .bodyValue(OrderResponse.from(grant)));
    }
}
