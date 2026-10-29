package co.com.ticketing.usecase.expirereservations;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.usecase.confirmorder.ConfirmOrderUseCase;
import co.com.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import co.com.ticketing.usecase.support.InMemoryTicketingStore;
import co.com.ticketing.usecase.support.RecordingPublisher;
import org.junit.jupiter.api.RepeatedTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer confirms at the last millisecond while the sweeper expires the same reservation. The
 * two run on different machines whose clocks disagree, so both believe they are right. The
 * conditional write decides: exactly one transition wins for every order.
 */
class ConfirmVersusExpireRaceTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant DEADLINE = T0.plus(Order.RESERVATION_TTL);
    private static final int ORDERS = 40;

    @RepeatedTest(5)
    void exactlyOneOfConfirmOrExpireShouldWin() {
        InMemoryTicketingStore store = new InMemoryTicketingStore();
        Clock atT0 = Clock.fixed(T0, ZoneOffset.UTC);
        store.eventRepository().save(Event.create("e-1", "Rock Fest", "Arena", T0.plusSeconds(86_400), ORDERS, T0)).block();
        PlaceOrderUseCase place = new PlaceOrderUseCase(store.eventRepository(), store.orderRepository(),
                store.idempotencyRepository(), new RecordingPublisher(), atT0);
        ReserveTicketsUseCase reserve = new ReserveTicketsUseCase(store.orderRepository(), store.stateChangeRepository(), atT0);
        List<Order> orders = Flux.range(0, ORDERS)
                .concatMap(i -> place.place("e-1", "c-" + i, 1, "k-" + i))
                .concatMap(order -> reserve.reserve(order.orderId()).thenReturn(order))
                .collectList().block();

        // The API's clock is 1 ms behind the worker's clock
        ConfirmOrderUseCase confirm = new ConfirmOrderUseCase(store.orderRepository(), store.ticketRepository(),
                store.stateChangeRepository(), new RecordingPublisher(), Clock.fixed(DEADLINE.minusMillis(1), ZoneOffset.UTC));
        ExpireReservationsUseCase sweeper = new ExpireReservationsUseCase(store.orderRepository(), store.ticketRepository(),
                store.stateChangeRepository(), new RecordingPublisher(), Clock.fixed(DEADLINE, ZoneOffset.UTC));

        Flux.fromIterable(orders)
                .flatMap(order -> confirm.requestConfirmation(order.orderId(), order.customerId())
                        .onErrorResume(_ -> Mono.empty())
                        .subscribeOn(Schedulers.parallel()))
                .mergeWith(sweeper.sweep().subscribeOn(Schedulers.parallel()).then(Mono.empty()))
                .then().block();

        for (Order order : orders) {
            Order result = store.orderRepository().findById(order.orderId()).block();
            List<TicketStatus> tickets = store.ticketRepository().findByOrderId(order.orderId())
                    .map(Ticket::status).collectList().block();
            assertThat(result.status()).isIn(OrderStatus.CONFIRMING, OrderStatus.EXPIRED);
            assertThat(tickets).containsOnly(result.status() == OrderStatus.CONFIRMING
                    ? TicketStatus.PENDING_CONFIRMATION : TicketStatus.AVAILABLE);
        }
        long confirmed = store.orders().stream().filter(order -> order.status() == OrderStatus.CONFIRMING).count();
        Inventory inventory = store.event("e-1").inventory();
        assertThat(inventory.pendingConfirmation()).isEqualTo((int) confirmed);
        assertThat(inventory.available()).isEqualTo(ORDERS - (int) confirmed);
        assertThat(inventory.reserved()).isZero();
    }
}
