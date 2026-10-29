package co.com.ticketing.usecase.getevent;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

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

    /**
     * Live availability: emits the inventory now and again each time it changes, polling every
     * {@code interval} and closing after {@code maxDuration} so idle streams do not hold connections.
     */
    public Flux<Inventory> watchInventory(String eventId, Duration interval, Duration maxDuration) {
        return get(eventId)
                .thenMany(Flux.interval(Duration.ZERO, interval)
                        .onBackpressureDrop()
                        .concatMap(_ -> get(eventId))
                        .map(Event::inventory)
                        .distinctUntilChanged()
                        .take(maxDuration));
    }
}
