package co.com.ticketing.usecase.assigncomplimentary;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.Ticket;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Grants complimentary tickets: final like a sale, taken from the same inventory, but not accountable. */
@RequiredArgsConstructor
public class AssignComplimentaryUseCase {

    private final EventRepository eventRepository;
    private final StateChangeRepository stateChangeRepository;
    private final Clock clock;

    public Mono<Order> assign(String eventId, String recipientId, int quantity) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("Event", eventId)))
                .flatMap(_ -> {
                    Instant now = clock.instant();
                    Order grant = Order.complimentary(UUID.randomUUID().toString(), eventId, recipientId, quantity, now);
                    List<Ticket> tickets = IntStream.range(0, quantity)
                            .mapToObj(_ -> Ticket.complimentary(UUID.randomUUID().toString(), grant.orderId(), eventId, now))
                            .toList();
                    return stateChangeRepository.apply(StateChange.complimentary(grant, tickets)).thenReturn(grant);
                });
    }
}
