package co.com.ticketing.usecase.placeorder;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Event EVENT = Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(3600), 10, NOW);
    private static final String SCOPED_KEY = "c-1#key-1";
    private static final String HASH = IdempotencyRecord.hashOf("e-1", "c-1", 2);

    @Mock
    private EventRepository eventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private IdempotencyRepository idempotencyRepository;
    @Mock
    private OrderEventPublisher orderEventPublisher;

    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(eventRepository, orderRepository, idempotencyRepository,
                orderEventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void storeSucceeds() {
        when(orderRepository.create(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void shouldStorePendingOrderAndQueueIt() {
        when(idempotencyRepository.findByKey(SCOPED_KEY)).thenReturn(Mono.empty());
        when(eventRepository.findById("e-1")).thenReturn(Mono.just(EVENT));
        storeSucceeds();
        when(orderEventPublisher.publishOrderPlaced(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place("e-1", "c-1", 2, "key-1"))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
                    assertThat(order.quantity()).isEqualTo(2);
                    verify(orderEventPublisher).publishOrderPlaced(order.orderId());
                })
                .verifyComplete();

        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(orderRepository).create(any(), record.capture());
        assertThat(record.getValue().key()).isEqualTo(SCOPED_KEY);
        assertThat(record.getValue().requestHash()).isEqualTo(HASH);
    }

    @Test
    void shouldReturnStoredOrderWhenQueueFails() {
        when(idempotencyRepository.findByKey(SCOPED_KEY)).thenReturn(Mono.empty());
        when(eventRepository.findById("e-1")).thenReturn(Mono.just(EVENT));
        storeSucceeds();
        when(orderEventPublisher.publishOrderPlaced(anyString())).thenReturn(Mono.error(new IllegalStateException("queue down")));

        StepVerifier.create(useCase.place("e-1", "c-1", 2, "key-1"))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.PENDING))
                .verifyComplete();
    }

    @Test
    void shouldReplayOrderForRepeatedRequest() {
        Order existing = Order.place("o-1", "e-1", "c-1", 2, NOW);
        when(idempotencyRepository.findByKey(SCOPED_KEY))
                .thenReturn(Mono.just(IdempotencyRecord.of(SCOPED_KEY, HASH, "o-1", NOW)));
        when(orderRepository.findById("o-1")).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.place("e-1", "c-1", 2, "key-1")).expectNext(existing).verifyComplete();

        verify(orderRepository, never()).create(any(), any());
        verify(orderEventPublisher, never()).publishOrderPlaced(anyString());
    }

    @Test
    void shouldRejectKeyReusedWithDifferentPayload() {
        when(idempotencyRepository.findByKey(SCOPED_KEY))
                .thenReturn(Mono.just(IdempotencyRecord.of(SCOPED_KEY, HASH, "o-1", NOW)));

        StepVerifier.create(useCase.place("e-1", "c-1", 3, "key-1"))
                .expectError(IdempotencyConflictException.class)
                .verify();
    }

    @Test
    void shouldReplayWinnerWhenSameKeyRaces() {
        Order winner = Order.place("o-winner", "e-1", "c-1", 2, NOW);
        when(idempotencyRepository.findByKey(SCOPED_KEY))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(IdempotencyRecord.of(SCOPED_KEY, HASH, "o-winner", NOW)));
        when(eventRepository.findById("e-1")).thenReturn(Mono.just(EVENT));
        when(orderRepository.create(any(), any())).thenReturn(Mono.error(new DuplicateIdempotencyKeyException(SCOPED_KEY)));
        when(orderRepository.findById("o-winner")).thenReturn(Mono.just(winner));

        StepVerifier.create(useCase.place("e-1", "c-1", 2, "key-1")).expectNext(winner).verifyComplete();

        verify(orderEventPublisher, never()).publishOrderPlaced(anyString());
    }

    @Test
    void shouldFailWhenEventDoesNotExist() {
        when(idempotencyRepository.findByKey(SCOPED_KEY)).thenReturn(Mono.empty());
        when(eventRepository.findById("e-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place("e-1", "c-1", 2, "key-1"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    void shouldRequireIdempotencyKey() {
        StepVerifier.create(useCase.place("e-1", "c-1", 2, " "))
                .expectError(DomainValidationException.class)
                .verify();
    }

    @Test
    void shouldRejectInvalidQuantityWithoutStoring() {
        when(idempotencyRepository.findByKey("c-1#key-1")).thenReturn(Mono.empty());
        when(eventRepository.findById("e-1")).thenReturn(Mono.just(EVENT));

        StepVerifier.create(useCase.place("e-1", "c-1", Order.MAX_TICKETS_PER_ORDER + 1, "key-1"))
                .expectError(DomainValidationException.class)
                .verify();

        verify(orderRepository, never()).create(any(), any());
    }
}
