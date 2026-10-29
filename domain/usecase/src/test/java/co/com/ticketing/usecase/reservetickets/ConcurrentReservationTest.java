package co.com.ticketing.usecase.reservetickets;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.ticketing.usecase.support.InMemoryTicketingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many customers compete for few tickets at the same time. Whatever the interleaving, exactly
 * the available tickets are reserved and nothing is oversold.
 */
class ConcurrentReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int CAPACITY = 50;
    private static final int CUSTOMERS = 200;

    private InMemoryTicketingStore store;
    private PlaceOrderUseCase placeOrder;
    private ReserveTicketsUseCase reserveTickets;

    @BeforeEach
    void setUp() {
        store = new InMemoryTicketingStore();
        OrderEventPublisher noQueue = _ -> Mono.empty();
        placeOrder = new PlaceOrderUseCase(store.eventRepository(), store.orderRepository(),
                store.idempotencyRepository(), noQueue, CLOCK);
        reserveTickets = new ReserveTicketsUseCase(store.orderRepository(), store.stateChangeRepository(), CLOCK);
        store.eventRepository()
                .save(Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(86_400), CAPACITY, NOW))
                .block();
    }

    private List<String> placeOneTicketOrders() {
        return Flux.range(0, CUSTOMERS)
                .flatMap(i -> placeOrder.place("e-1", "customer-" + i, 1, "key-" + i)
                        .subscribeOn(Schedulers.parallel()))
                .map(Order::orderId)
                .collectList()
                .block();
    }

    private void reserveConcurrently(List<String> orderIds) {
        StepVerifier.create(Flux.fromIterable(orderIds)
                        .flatMap(orderId -> reserveTickets.reserve(orderId).subscribeOn(Schedulers.parallel())))
                .verifyComplete();
    }

    private Map<OrderStatus, Long> countByStatus() {
        return store.orders().stream().collect(Collectors.groupingBy(Order::status, Collectors.counting()));
    }

    @RepeatedTest(5)
    void exactlyTheAvailableTicketsShouldBeReserved() {
        reserveConcurrently(placeOneTicketOrders());

        assertThat(countByStatus())
                .containsEntry(OrderStatus.AWAITING_CONFIRMATION, (long) CAPACITY)
                .containsEntry(OrderStatus.REJECTED, (long) (CUSTOMERS - CAPACITY));
        assertThat(store.ticketCount()).isEqualTo(CAPACITY);
        assertThat(store.event("e-1").inventory().available()).isZero();
        assertThat(store.event("e-1").inventory().reserved()).isEqualTo(CAPACITY);
    }

    @RepeatedTest(5)
    void duplicatedMessagesShouldNotReserveTwice() {
        List<String> orderIds = placeOneTicketOrders();
        // At-least-once delivery: every message arrives three times, concurrently
        List<String> deliveries = Flux.fromIterable(orderIds).flatMap(id -> Flux.just(id, id, id)).collectList().block();

        reserveConcurrently(deliveries);

        assertThat(countByStatus()).containsEntry(OrderStatus.AWAITING_CONFIRMATION, (long) CAPACITY);
        assertThat(store.ticketCount()).isEqualTo(CAPACITY);
        assertThat(store.event("e-1").inventory().total()).isEqualTo(CAPACITY);
    }

    @Test
    void repeatedRequestsWithSameKeyShouldCreateOneOrder() {
        List<Order> results = Flux.range(0, 20)
                .flatMap(_ -> placeOrder.place("e-1", "customer-1", 2, "same-key").subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertThat(results).extracting(Order::orderId).containsOnly(results.getFirst().orderId());
        assertThat(store.orders()).hasSize(1);
    }

    @Test
    void everyTransitionShouldBeAudited() {
        reserveConcurrently(placeOneTicketOrders());

        // One creation entry per order, one transition per order, one entry per reserved ticket
        assertThat(store.audit()).hasSize(CUSTOMERS + CUSTOMERS + CAPACITY);
    }
}
