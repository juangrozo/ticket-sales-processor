package co.com.ticketing.usecase.support;

import co.com.ticketing.model.audit.AuditEntry;
import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * In-memory store with the guarantees the DynamoDB adapter provides: every write is conditional
 * and each state change is all-or-nothing. A single monitor plays the role of the DynamoDB
 * transaction; it guards in-memory map operations only, never I/O.
 */
public class InMemoryTicketingStore {

    private final Map<String, Event> events = new HashMap<>();
    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();

    private synchronized <T> T atomically(Supplier<T> operation) {
        return operation.get();
    }

    public EventRepository eventRepository() {
        return new EventRepository() {
            @Override
            public Mono<Event> save(Event event) {
                return Mono.fromSupplier(() -> atomically(() -> {
                    events.put(event.eventId(), event);
                    return event;
                }));
            }

            @Override
            public Mono<Event> findById(String eventId) {
                return Mono.fromSupplier(() -> atomically(() -> events.get(eventId)));
            }

            @Override
            public Flux<Event> findAll() {
                return Flux.defer(() -> Flux.fromIterable(atomically(() -> List.copyOf(events.values()))));
            }
        };
    }

    public OrderRepository orderRepository() {
        return new OrderRepository() {
            @Override
            public Mono<Order> create(Order order, IdempotencyRecord record) {
                return Mono.fromSupplier(() -> atomically(() -> {
                    if (idempotency.containsKey(record.key())) {
                        throw new DuplicateIdempotencyKeyException(record.key());
                    }
                    idempotency.put(record.key(), record);
                    orders.put(order.orderId(), order);
                    audit.add(AuditEntry.orderCreated(order));
                    return order;
                }));
            }

            @Override
            public Mono<Order> findById(String orderId) {
                return Mono.fromSupplier(() -> atomically(() -> orders.get(orderId)));
            }

            @Override
            public Flux<Order> findByStatusChangedBetween(OrderStatus status, Instant from, Instant to, int limit) {
                return Flux.defer(() -> Flux.fromIterable(atomically(() -> orders.values().stream()
                        .filter(order -> order.status() == status)
                        .filter(order -> !order.statusChangedAt().isBefore(from) && !order.statusChangedAt().isAfter(to))
                        .limit(limit)
                        .toList())));
            }
        };
    }

    public IdempotencyRepository idempotencyRepository() {
        return scopedKey -> Mono.fromSupplier(() -> atomically(() -> idempotency.get(scopedKey)));
    }

    public TicketRepository ticketRepository() {
        return orderId -> Flux.defer(() -> Flux.fromIterable(atomically(() -> tickets.values().stream()
                .filter(ticket -> ticket.orderId().equals(orderId))
                .toList())));
    }

    public StateChangeRepository stateChangeRepository() {
        return change -> Mono.<Void>fromSupplier(() -> atomically(() -> {
            applyAtomically(change);
            return null;
        }));
    }

    private void applyAtomically(StateChange change) {
        String orderId = change.orderAfter().orderId();
        Order expected = change.orderBefore();
        Order stored = orders.get(orderId);
        boolean orderMatches = change.createsOrder()
                ? stored == null
                : stored != null && stored.version() == expected.version() && stored.status() == expected.status();
        if (!orderMatches) {
            throw new ConcurrentUpdateException("Order", orderId);
        }
        for (StateChange.TicketChange ticketChange : change.changedTickets()) {
            Ticket current = tickets.get(ticketChange.before().ticketId());
            if (current == null || current.status() != ticketChange.before().status()) {
                throw new ConcurrentUpdateException("Ticket", ticketChange.before().ticketId());
            }
        }
        Event movedEvent = null;
        if (change.inventoryMove() != null) {
            var move = change.inventoryMove();
            Event event = events.get(move.eventId());
            if (event == null || event.inventory().count(move.from()) < move.quantity()) {
                throw new InsufficientInventoryException(move.eventId(), move.quantity());
            }
            movedEvent = event.moveTickets(move.from(), move.to(), move.quantity());
        }
        // Every condition passed: apply all writes together
        orders.put(orderId, change.orderAfter());
        if (movedEvent != null) {
            events.put(movedEvent.eventId(), movedEvent);
        }
        change.createdTickets().forEach(ticket -> tickets.put(ticket.ticketId(), ticket));
        change.changedTickets().forEach(ticketChange -> tickets.put(ticketChange.after().ticketId(), ticketChange.after()));
        audit.addAll(change.auditEntries());
    }

    public List<Order> orders() {
        return atomically(() -> List.copyOf(orders.values()));
    }

    public Event event(String eventId) {
        return atomically(() -> events.get(eventId));
    }

    public int ticketCount() {
        return atomically(tickets::size);
    }

    public List<AuditEntry> audit() {
        return atomically(() -> List.copyOf(audit));
    }
}
