package co.com.ticketing.api;

import co.com.ticketing.api.config.SecurityHeadersConfig;
import co.com.ticketing.api.error.ErrorResponseMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderDetails;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.ticketing.usecase.getevent.GetEventUseCase;
import co.com.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouterRestTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-12-15T20:00:00Z");
    private static final Event EVENT = Event.create("e-1", "Rock Fest", "Arena", STARTS_AT, 100, NOW);
    private static final Order ORDER = Order.place("o-1", "e-1", "c-1", 2, NOW);

    @Mock
    private CreateEventUseCase createEventUseCase;
    @Mock
    private GetEventUseCase getEventUseCase;
    @Mock
    private PlaceOrderUseCase placeOrderUseCase;
    @Mock
    private GetOrderUseCase getOrderUseCase;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var router = new RouterRest().routerFunction(
                new EventHandler(createEventUseCase, getEventUseCase),
                new OrderHandler(placeOrderUseCase, getOrderUseCase),
                new ErrorResponseMapper());
        client = WebTestClient.bindToRouterFunction(router).webFilter(new SecurityHeadersConfig()).build();
    }

    @Test
    void shouldCreateEvent() {
        when(createEventUseCase.create("Rock Fest", "Arena", STARTS_AT, 100)).thenReturn(Mono.just(EVENT));

        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Rock Fest\",\"venue\":\"Arena\",\"startsAt\":\"2026-12-15T20:00:00Z\",\"capacity\":100}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/events/e-1")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("e-1")
                .jsonPath("$.inventory.available").isEqualTo(100)
                .jsonPath("$.startsAt").isEqualTo("2026-12-15T20:00:00Z");
    }

    @Test
    void shouldRejectEventWithoutCapacity() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Rock Fest\",\"venue\":\"Arena\",\"startsAt\":\"2026-12-15T20:00:00Z\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("capacity is required");
    }

    @Test
    void shouldRejectMissingBody() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void malformedJsonShouldBeBadRequest() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldMapDomainValidationToBadRequest() {
        when(createEventUseCase.create(any(), any(), any(), anyInt()))
                .thenReturn(Mono.error(new DomainValidationException("Event date must be in the future")));

        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Rock Fest\",\"venue\":\"Arena\",\"startsAt\":\"2020-01-01T00:00:00Z\",\"capacity\":1}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Event date must be in the future")
                .jsonPath("$.instance").isEqualTo("/events");
    }

    @Test
    void shouldGetEvent() {
        when(getEventUseCase.get("e-1")).thenReturn(Mono.just(EVENT));

        client.get().uri("/events/e-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Rock Fest");
    }

    @Test
    void unknownEventShouldBeNotFound() {
        when(getEventUseCase.get("e-9")).thenReturn(Mono.error(new ResourceNotFoundException("Event", "e-9")));

        client.get().uri("/events/e-9").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("Event e-9 not found");
    }

    @Test
    void shouldListEvents() {
        when(getEventUseCase.list()).thenReturn(Flux.just(EVENT));

        client.get().uri("/events").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].eventId").isEqualTo("e-1");
    }

    @Test
    void shouldAcceptOrder() {
        when(placeOrderUseCase.place("e-1", "c-1", 2, "key-1")).thenReturn(Mono.just(ORDER));

        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .header(OrderHandler.IDEMPOTENCY_HEADER, "key-1")
                .bodyValue("{\"eventId\":\"e-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().location("/orders/o-1")
                .expectBody()
                .jsonPath("$.orderStatus").isEqualTo("PENDING")
                .jsonPath("$.ticketStatus").doesNotExist()
                .jsonPath("$.tickets").isEmpty();
    }

    @Test
    void orderWithoutCustomerShouldBeBadRequest() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.IDEMPOTENCY_HEADER, "key-1")
                .bodyValue("{\"eventId\":\"e-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(placeOrderUseCase, never()).place(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void orderWithTooLongCustomerShouldBeBadRequest() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.CUSTOMER_HEADER, "c".repeat(65))
                .bodyValue("{\"eventId\":\"e-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void orderWithoutQuantityShouldBeBadRequest() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .bodyValue("{\"eventId\":\"e-1\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void orderWithoutBodyShouldBeBadRequest() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void reusedIdempotencyKeyShouldBeUnprocessable() {
        when(placeOrderUseCase.place(anyString(), anyString(), anyInt(), eq("key-1")))
                .thenReturn(Mono.error(new IdempotencyConflictException()));

        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .header(OrderHandler.IDEMPOTENCY_HEADER, "key-1")
                .bodyValue("{\"eventId\":\"e-1\",\"quantity\":3}")
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void shouldGetOrderWithBothStatuses() {
        Order reserved = ORDER.apply(new Reserve(NOW));
        OrderDetails details = new OrderDetails(reserved, List.of(
                Ticket.reserved("t-1", "o-1", "e-1", NOW), Ticket.reserved("t-2", "o-1", "e-1", NOW)));
        when(getOrderUseCase.get("o-1")).thenReturn(Mono.just(details));

        client.get().uri("/orders/o-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderStatus").isEqualTo("AWAITING_CONFIRMATION")
                .jsonPath("$.ticketStatus").isEqualTo("RESERVED")
                .jsonPath("$.expiresAt").isEqualTo("2026-11-01T10:10:00Z")
                .jsonPath("$.tickets.length()").isEqualTo(2);
    }

    @Test
    void unexpectedErrorShouldNotLeakDetails() {
        when(getOrderUseCase.get("o-1")).thenReturn(Mono.error(new IllegalStateException("table orders unreachable")));

        client.get().uri("/orders/o-1").exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.detail").isEqualTo("Unexpected error");
    }
}
