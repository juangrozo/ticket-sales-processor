package co.com.ticketing.api;

import co.com.ticketing.api.config.ApiProperties;
import co.com.ticketing.api.config.SecurityHeadersConfig;
import co.com.ticketing.api.error.ErrorResponseMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderDetails;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.usecase.assigncomplimentary.AssignComplimentaryUseCase;
import co.com.ticketing.usecase.confirmorder.ConfirmOrderUseCase;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final ApiProperties PROPERTIES =
            new ApiProperties("admin-secret", Duration.ofSeconds(2), Duration.ofSeconds(10), 20, 10);

    @Mock
    private CreateEventUseCase createEventUseCase;
    @Mock
    private GetEventUseCase getEventUseCase;
    @Mock
    private PlaceOrderUseCase placeOrderUseCase;
    @Mock
    private GetOrderUseCase getOrderUseCase;
    @Mock
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Mock
    private AssignComplimentaryUseCase assignComplimentaryUseCase;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var router = new RouterRest().routerFunction(
                new EventHandler(createEventUseCase, getEventUseCase, assignComplimentaryUseCase,
                        new AdminAuthorizer(PROPERTIES), PROPERTIES),
                new OrderHandler(placeOrderUseCase, getOrderUseCase, confirmOrderUseCase),
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
        when(getOrderUseCase.get("o-1", "c-1")).thenReturn(Mono.just(details));

        client.get().uri("/orders/o-1").header(OrderHandler.CUSTOMER_HEADER, "c-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderStatus").isEqualTo("AWAITING_CONFIRMATION")
                .jsonPath("$.ticketStatus").isEqualTo("RESERVED")
                .jsonPath("$.expiresAt").isEqualTo("2026-11-01T10:10:00Z")
                .jsonPath("$.tickets.length()").isEqualTo(2);
    }

    private static Event withInventory(Inventory inventory) {
        return new Event("e-1", "Rock Fest", "Arena", STARTS_AT, 100, inventory, NOW);
    }

    @Test
    void shouldReturnAvailabilityConsideringReservedAndSold() {
        Event busy = withInventory(new Inventory(92, 5, 0, 3, 0));
        when(getEventUseCase.get("e-1")).thenReturn(Mono.just(busy));

        client.get().uri("/events/e-1/availability").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(92)
                .jsonPath("$.reserved").isEqualTo(5)
                .jsonPath("$.sold").isEqualTo(3);
    }

    @Test
    void shouldStreamAvailabilityAsServerSentEvents() {
        when(getEventUseCase.get("e-1")).thenReturn(Mono.just(EVENT));
        when(getEventUseCase.watchInventory("e-1", PROPERTIES.availabilityPollInterval(), PROPERTIES.availabilityMaxDuration()))
                .thenReturn(Flux.just(EVENT.inventory()));

        String body = client.get().uri("/events/e-1/availability/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class).getResponseBody().blockFirst();

        assertThat(body).contains("\"available\":100");
    }

    @Test
    void shouldReportAccountingWithoutComplimentaryTickets() {
        Event event = withInventory(new Inventory(94, 0, 0, 4, 2));
        when(getEventUseCase.get("e-1")).thenReturn(Mono.just(event));

        client.get().uri("/events/e-1/report").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accounting.soldTickets").isEqualTo(4)
                .jsonPath("$.inventory.complimentary").isEqualTo(2)
                .jsonPath("$.inventory.total").isEqualTo(100)
                .jsonPath("$.operational.available").isEqualTo(94);
    }

    @Test
    void adminShouldAssignComplimentaryTickets() {
        when(assignComplimentaryUseCase.assign("e-1", "vip-1", 2))
                .thenReturn(Mono.just(Order.complimentary("o-9", "e-1", "vip-1", 2, NOW)));

        client.post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .header(AdminAuthorizer.ADMIN_KEY_HEADER, "admin-secret")
                .bodyValue("{\"recipientId\":\"vip-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/orders/o-9")
                .expectBody()
                .jsonPath("$.type").isEqualTo("COMPLIMENTARY")
                .jsonPath("$.orderStatus").isEqualTo("CONFIRMED");
    }

    @Test
    void complimentaryWithWrongKeyShouldBeForbidden() {
        client.post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .header(AdminAuthorizer.ADMIN_KEY_HEADER, "guess")
                .bodyValue("{\"recipientId\":\"vip-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid admin key");

        verify(assignComplimentaryUseCase, never()).assign(anyString(), anyString(), anyInt());
    }

    @Test
    void complimentaryWithoutKeyOrQuantityShouldBeRejected() {
        client.post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"recipientId\":\"vip-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isForbidden();
        client.post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .header(AdminAuthorizer.ADMIN_KEY_HEADER, "admin-secret")
                .bodyValue("{\"recipientId\":\"vip-1\"}")
                .exchange()
                .expectStatus().isBadRequest();
        client.post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .header(AdminAuthorizer.ADMIN_KEY_HEADER, "admin-secret")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void adminApiShouldBeDisabledWithoutConfiguredKey() {
        var disabled = new AdminAuthorizer(new ApiProperties(" ", Duration.ofSeconds(1), Duration.ofSeconds(1), 20, 10));
        var router = new RouterRest().routerFunction(
                new EventHandler(createEventUseCase, getEventUseCase, assignComplimentaryUseCase, disabled, PROPERTIES),
                new OrderHandler(placeOrderUseCase, getOrderUseCase, confirmOrderUseCase),
                new ErrorResponseMapper());

        WebTestClient.bindToRouterFunction(router).build()
                .post().uri("/events/e-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                .header(AdminAuthorizer.ADMIN_KEY_HEADER, "")
                .bodyValue("{\"recipientId\":\"vip-1\",\"quantity\":2}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.detail").isEqualTo("Admin API is disabled");
    }

    @Test
    void shouldAcceptConfirmation() {
        Order confirming = ORDER.apply(new Reserve(NOW)).apply(new RequestConfirmation(NOW.plusSeconds(30)));
        when(confirmOrderUseCase.requestConfirmation("o-1", "c-1")).thenReturn(Mono.just(confirming));

        client.post().uri("/orders/o-1/confirm")
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.orderStatus").isEqualTo("CONFIRMING");
    }

    @Test
    void expiredReservationShouldBeGone() {
        when(confirmOrderUseCase.requestConfirmation("o-1", "c-1")).thenReturn(Mono.error(new ReservationExpiredException("o-1")));

        client.post().uri("/orders/o-1/confirm")
                .header(OrderHandler.CUSTOMER_HEADER, "c-1")
                .exchange()
                .expectStatus().isEqualTo(410);
    }

    @Test
    void confirmationWithoutCustomerShouldBeBadRequest() {
        client.post().uri("/orders/o-1/confirm").exchange().expectStatus().isBadRequest();
    }

    @Test
    void readingAnOrderShouldRequireTheCustomer() {
        client.get().uri("/orders/o-1").exchange().expectStatus().isBadRequest();

        verify(getOrderUseCase, never()).get(anyString(), anyString());
    }

    @Test
    void unexpectedErrorShouldNotLeakDetails() {
        when(getOrderUseCase.get("o-1", "c-1")).thenReturn(Mono.error(new IllegalStateException("table orders unreachable")));

        client.get().uri("/orders/o-1").header(OrderHandler.CUSTOMER_HEADER, "c-1").exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.detail").isEqualTo("Unexpected error");
    }
}
