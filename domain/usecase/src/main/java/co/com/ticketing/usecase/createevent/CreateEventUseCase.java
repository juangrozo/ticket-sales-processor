package co.com.ticketing.usecase.createevent;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class CreateEventUseCase {

    private final EventRepository eventRepository;
    private final Clock clock;

    public Mono<Event> create(String name, String venue, Instant startsAt, int capacity) {
        return Mono.fromCallable(() -> Event.create(UUID.randomUUID().toString(), name, venue, startsAt, capacity, clock.instant()))
                .flatMap(eventRepository::save);
    }
}
