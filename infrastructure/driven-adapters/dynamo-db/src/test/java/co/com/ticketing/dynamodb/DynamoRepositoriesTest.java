package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.EventItemMapper;
import co.com.ticketing.dynamodb.mapper.IdempotencyItemMapper;
import co.com.ticketing.dynamodb.mapper.OrderItemMapper;
import co.com.ticketing.dynamodb.mapper.TicketItemMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.Ticket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoRepositoriesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final DynamoDbTables TABLES = new DynamoDbTables("events", "orders", "tickets", "audit", "idempotency");
    private static final Event EVENT = Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(3600), 10, NOW);
    private static final Order ORDER = Order.place("o-1", "e-1", "c-1", 2, NOW);

    @Mock
    private DynamoDbAsyncClient client;

    private static CompletableFuture<GetItemResponse> item(java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item) {
        return CompletableFuture.completedFuture(GetItemResponse.builder().item(item).build());
    }

    private static CompletableFuture<GetItemResponse> noItem() {
        return CompletableFuture.completedFuture(GetItemResponse.builder().build());
    }

    @Test
    void shouldSaveEventOnlyIfNew() {
        when(client.putItem(any(PutItemRequest.class))).thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(new DynamoEventRepository(client, TABLES).save(EVENT)).expectNext(EVENT).verifyComplete();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression()).isEqualTo("attribute_not_exists(#eventId)");
    }

    @Test
    void shouldFindEventById() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(item(EventItemMapper.toItem(EVENT)));

        StepVerifier.create(new DynamoEventRepository(client, TABLES).findById("e-1")).expectNext(EVENT).verifyComplete();
    }

    @Test
    void missingEventShouldBeEmpty() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(noItem());

        StepVerifier.create(new DynamoEventRepository(client, TABLES).findById("e-1")).verifyComplete();
    }

    @Test
    void shouldListEventsAcrossPages() {
        when(client.scanPaginator(any(ScanRequest.class))).thenCallRealMethod();
        when(client.scan(any(ScanRequest.class))).thenReturn(CompletableFuture.completedFuture(
                ScanResponse.builder().items(EventItemMapper.toItem(EVENT)).build()));

        StepVerifier.create(new DynamoEventRepository(client, TABLES).findAll()).expectNext(EVENT).verifyComplete();
    }

    @Test
    void shouldCreateOrderWithIdempotencyRecordAtomically() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW);

        StepVerifier.create(new DynamoOrderRepository(client, TABLES).create(ORDER, record)).expectNext(ORDER).verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).put().tableName()).isEqualTo("idempotency");
        assertThat(items.get(0).put().conditionExpression()).isEqualTo("attribute_not_exists(#key)");
        assertThat(items.get(1).put().tableName()).isEqualTo("orders");
        assertThat(items.get(2).put().tableName()).isEqualTo("audit");
    }

    @Test
    void takenIdempotencyKeyShouldBeReported() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(
                TransactionCanceledException.builder().cancellationReasons(
                        CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("None").build()).build()));

        StepVerifier.create(new DynamoOrderRepository(client, TABLES)
                        .create(ORDER, IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW)))
                .expectError(DuplicateIdempotencyKeyException.class)
                .verify();
    }

    @Test
    void otherCreationFailuresShouldPassThrough() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(
                TransactionCanceledException.builder().cancellationReasons(
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("ConditionalCheckFailed").build()).build()));

        StepVerifier.create(new DynamoOrderRepository(client, TABLES)
                        .create(ORDER, IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW)))
                .expectError(TransactionCanceledException.class)
                .verify();
    }

    @Test
    void shouldFindOrderWithConsistentRead() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(item(OrderItemMapper.toItem(ORDER)));

        StepVerifier.create(new DynamoOrderRepository(client, TABLES).findById("o-1")).expectNext(ORDER).verifyComplete();

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void missingOrderShouldBeEmpty() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(noItem());

        StepVerifier.create(new DynamoOrderRepository(client, TABLES).findById("o-1")).verifyComplete();
    }

    @Test
    void shouldFindTicketsOfOrder() {
        Ticket ticket = Ticket.reserved("t-1", "o-1", "e-1", NOW);
        when(client.queryPaginator(any(QueryRequest.class))).thenCallRealMethod();
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of(TicketItemMapper.toItem(ticket))).build()));

        StepVerifier.create(new DynamoTicketRepository(client, TABLES).findByOrderId("o-1")).expectNext(ticket).verifyComplete();
    }

    @Test
    void shouldReturnLiveIdempotencyRecord() {
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(item(IdempotencyItemMapper.toItem(record)));
        var repository = new DynamoIdempotencyRepository(client, TABLES, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        StepVerifier.create(repository.findByKey("c-1#k")).expectNext(record).verifyComplete();
    }

    @Test
    void shouldIgnoreExpiredIdempotencyRecordNotYetDeletedByTtl() {
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(item(IdempotencyItemMapper.toItem(record)));
        var repository = new DynamoIdempotencyRepository(client, TABLES,
                Clock.fixed(record.expiresAt().plusSeconds(1), ZoneOffset.UTC));

        StepVerifier.create(repository.findByKey("c-1#k")).verifyComplete();
    }

    @Test
    void missingIdempotencyRecordShouldBeEmpty() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(noItem());
        var repository = new DynamoIdempotencyRepository(client, TABLES, Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(repository.findByKey("c-1#k")).verifyComplete();
    }
}
