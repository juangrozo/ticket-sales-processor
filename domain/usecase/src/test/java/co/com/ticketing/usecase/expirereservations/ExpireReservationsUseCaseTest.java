package co.com.ticketing.usecase.expirereservations;

import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import co.com.ticketing.usecase.expirereservations.ExpireReservationsUseCase.SweepResult;
import co.com.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import co.com.ticketing.usecase.support.InMemoryTicketingStore;
import co.com.ticketing.usecase.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpireReservationsUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Clock AT_T0 = Clock.fixed(T0, ZoneOffset.UTC);

    private InMemoryTicketingStore store;
    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() {
        store = new InMemoryTicketingStore();
        publisher = new RecordingPublisher();
        store.eventRepository().save(Event.create("e-1", "Rock Fest", "Arena", T0.plusSeconds(86_400), 10, T0)).block();
    }

    private ExpireReservationsUseCase sweeperAt(Instant now) {
        return new ExpireReservationsUseCase(store.orderRepository(), store.ticketRepository(),
                store.stateChangeRepository(), publisher, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Order placeAt(Instant at, String key) {
        return new PlaceOrderUseCase(store.eventRepository(), store.orderRepository(), store.idempotencyRepository(),
                new RecordingPublisher(), Clock.fixed(at, ZoneOffset.UTC))
                .place("e-1", "c-" + key, 2, key).block();
    }

    private Order reservedAt(Instant at, String key) {
        Order order = placeAt(at, key);
        new ReserveTicketsUseCase(store.orderRepository(), store.stateChangeRepository(), Clock.fixed(at, ZoneOffset.UTC))
                .reserve(order.orderId()).block();
        return store.orderRepository().findById(order.orderId()).block();
    }

    @Test
    void shouldReleaseReservationsPastTheirTimeLimit() {
        Order order = reservedAt(T0, "k1");

        StepVerifier.create(sweeperAt(order.expiresAt()).sweep())
                .expectNext(new SweepResult(1, 0, 0))
                .verifyComplete();

        assertThat(store.orderRepository().findById(order.orderId()).block().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(store.ticketRepository().findByOrderId(order.orderId()).map(Ticket::status).collectList().block())
                .containsOnly(TicketStatus.AVAILABLE);
        assertThat(store.event("e-1").inventory().available()).isEqualTo(10);
        assertThat(store.event("e-1").inventory().reserved()).isZero();
    }

    @Test
    void shouldKeepReservationsStillInTime() {
        Order order = reservedAt(T0, "k1");

        StepVerifier.create(sweeperAt(order.expiresAt().minusMillis(1)).sweep())
                .expectNext(new SweepResult(0, 0, 0))
                .verifyComplete();
    }

    @Test
    void shouldRecheckDeadlineOfStaleIndexEntries() {
        Order notExpiredYet = Order.place("o-1", "e-1", "c-1", 1, T0).apply(new Reserve(T0));
        OrderRepository staleIndex = mock(OrderRepository.class);
        when(staleIndex.findByStatusChangedBetween(any(), any(), any(), anyInt()))
                .thenReturn(Flux.just(notExpiredYet)).thenReturn(Flux.empty());
        var sweeper = new ExpireReservationsUseCase(staleIndex, store.ticketRepository(), store.stateChangeRepository(),
                publisher, AT_T0);

        StepVerifier.create(sweeper.sweep()).expectNext(new SweepResult(0, 0, 0)).verifyComplete();
    }

    @Test
    void sweepingTwiceShouldExpireOnce() {
        Order order = reservedAt(T0, "k1");
        Order staleCopy = order;
        OrderRepository index = mock(OrderRepository.class);
        // The second worker still sees the old snapshot in the index
        when(index.findByStatusChangedBetween(any(), any(), any(), anyInt()))
                .thenReturn(Flux.just(staleCopy)).thenReturn(Flux.empty()).thenReturn(Flux.empty());
        sweeperAt(order.expiresAt()).sweep().block();
        var secondWorker = new ExpireReservationsUseCase(index, store.ticketRepository(), store.stateChangeRepository(),
                publisher, Clock.fixed(order.expiresAt(), ZoneOffset.UTC));

        StepVerifier.create(secondWorker.sweep()).expectNext(new SweepResult(0, 0, 0)).verifyComplete();
        assertThat(store.event("e-1").inventory().available()).isEqualTo(10);
    }

    @Test
    void shouldRequeueOrdersStuckInPendingOrConfirming() {
        Order stuckPending = placeAt(T0, "k1");
        Order reserved = reservedAt(T0, "k2");
        Order confirming = reserved.apply(new RequestConfirmation(T0.plusSeconds(5)));
        store.stateChangeRepository().apply(StateChange.transition(reserved, confirming,
                store.ticketRepository().findByOrderId(reserved.orderId()).collectList().block(),
                TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)).block();

        StepVerifier.create(sweeperAt(T0.plus(ExpireReservationsUseCase.STALE_AFTER).plusSeconds(10)).sweep())
                .expectNext(new SweepResult(0, 1, 1))
                .verifyComplete();

        assertThat(publisher.placed()).containsExactly(stuckPending.orderId());
        assertThat(publisher.confirmations()).containsExactly(confirming.orderId());
    }

    @Test
    void shouldNotRequeueRecentOrVeryOldOrders() {
        placeAt(T0, "k1");

        StepVerifier.create(sweeperAt(T0.plusSeconds(10)).sweep()).expectNext(new SweepResult(0, 0, 0)).verifyComplete();
        StepVerifier.create(sweeperAt(T0.plus(ExpireReservationsUseCase.GIVE_UP_AFTER).plusSeconds(1)).sweep())
                .expectNext(new SweepResult(0, 0, 0))
                .verifyComplete();
    }

    @Test
    void failedRequeueShouldNotBreakTheSweep() {
        placeAt(T0, "k1");
        var failingPublisher = new RecordingPublisher() {
            @Override
            public Mono<Void> publishOrderPlaced(String orderId) {
                return Mono.error(new IllegalStateException("queue down"));
            }
        };
        var sweeper = new ExpireReservationsUseCase(store.orderRepository(), store.ticketRepository(),
                store.stateChangeRepository(), failingPublisher,
                Clock.fixed(T0.plus(ExpireReservationsUseCase.STALE_AFTER), ZoneOffset.UTC));

        StepVerifier.create(sweeper.sweep()).expectNext(new SweepResult(0, 0, 0)).verifyComplete();
    }

    @Test
    void periodicSweepShouldSurviveFailures() {
        OrderRepository broken = mock(OrderRepository.class);
        when(broken.findByStatusChangedBetween(any(), any(), any(), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("table down")));
        var sweeper = new ExpireReservationsUseCase(broken, store.ticketRepository(), store.stateChangeRepository(),
                publisher, AT_T0);

        StepVerifier.withVirtualTime(() -> sweeper.sweepEvery(Duration.ofSeconds(30)).take(1))
                .thenAwait(Duration.ofSeconds(30))
                .expectNoEvent(Duration.ofSeconds(1))
                .thenCancel()
                .verify();
    }

    @Test
    void periodicSweepShouldEmitEachRun() {
        var sweeper = sweeperAt(T0);

        StepVerifier.withVirtualTime(() -> sweeper.sweepEvery(Duration.ofSeconds(30)).take(2))
                .thenAwait(Duration.ofSeconds(60))
                .expectNext(new SweepResult(0, 0, 0), new SweepResult(0, 0, 0))
                .verifyComplete();
    }
}
