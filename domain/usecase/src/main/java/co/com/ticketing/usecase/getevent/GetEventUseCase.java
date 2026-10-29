package co.com.ticketing.usecase.getevent;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class GetEventUseCase {

    private final EventRepository eventRepository;

    public Mono<Event> get(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("Event", eventId)));
    }

    public Flux<Event> list() {
        return eventRepository.findAll();
    }
}
