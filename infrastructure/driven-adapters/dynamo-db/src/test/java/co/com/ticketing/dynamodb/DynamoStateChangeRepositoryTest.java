package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.StateChange.TicketChange;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoStateChangeRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final DynamoDbTables TABLES = new DynamoDbTables("events", "orders", "tickets", "audit", "idempotency");
    private static final Order PENDING = Order.place("o-1", "e-1", "c-1", 2, NOW);
    private static final List<Ticket> TICKETS = List.of(
            Ticket.reserved("t-1", "o-1", "e-1", NOW),
            Ticket.reserved("t-2", "o-1", "e-1", NOW));
    private static final StateChange RESERVATION = StateChange.reservation(PENDING, PENDING.apply(new Reserve(NOW)), TICKETS);

    @Mock
    private DynamoDbAsyncClient client;

    private DynamoStateChangeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DynamoStateChangeRepository(client, TABLES);
    }

    private static CompletableFuture<TransactWriteItemsResponse> success() {
        return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
    }

    private static CompletableFuture<TransactWriteItemsResponse> canceled(String... codes) {
        List<CancellationReason> reasons = java.util.Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList();
        return CompletableFuture.failedFuture(TransactionCanceledException.builder()
                .cancellationReasons(reasons).message("canceled").build());
    }

    @Test
    void reservationShouldBeOneTransactionWithConditionsOnEveryWrite() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(success());

        StepVerifier.create(repository.apply(RESERVATION)).verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        List<TransactWriteItem> items = captor.getValue().transactItems();
        // order + inventory + 2 tickets + 3 audit entries
        assertThat(items).hasSize(7);
        assertThat(items.get(0).put().tableName()).isEqualTo("orders");
        assertThat(items.get(0).put().conditionExpression()).contains("#version = :expectedVersion");
        assertThat(items.get(1).update().updateExpression()).isEqualTo("ADD #from :decrement, #to :increment");
        assertThat(items.get(1).update().conditionExpression()).contains("#from >= :quantity");
        assertThat(items.get(1).update().expressionAttributeNames())
                .containsEntry("#from", "available").containsEntry("#to", "reserved");
        assertThat(items.get(2).put().conditionExpression()).isEqualTo("attribute_not_exists(#ticketId)");
        assertThat(items.subList(4, 7)).allSatisfy(item -> assertThat(item.put().tableName()).isEqualTo("audit"));
    }

    @Test
    void failedInventoryConditionShouldMeanInsufficientInventory() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("None", "ConditionalCheckFailed", "None", "None", "None", "None", "None"));

        StepVerifier.create(repository.apply(RESERVATION))
                .expectError(InsufficientInventoryException.class)
                .verify();
    }

    @Test
    void failedOrderConditionShouldMeanConcurrentUpdate() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("ConditionalCheckFailed", "None", "None", "None", "None", "None", "None"));

        StepVerifier.create(repository.apply(RESERVATION))
                .expectError(ConcurrentUpdateException.class)
                .verify();
    }

    @Test
    void failedTicketConditionShouldMeanConcurrentUpdate() {
        Ticket before = TICKETS.getFirst();
        StateChange change = new StateChange(PENDING, PENDING, null, List.of(),
                List.of(new TicketChange(before, before.transitionTo(TicketStatus.PENDING_CONFIRMATION, NOW))));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("None", "ConditionalCheckFailed", "None", "None"));

        StepVerifier.create(repository.apply(change))
                .expectError(ConcurrentUpdateException.class)
                .verify();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems().get(1).put().conditionExpression()).isEqualTo("#status = :expectedStatus");
    }

    @Test
    void transactionConflictShouldBeRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("TransactionConflict", "None"))
                .thenReturn(success());

        StepVerifier.create(repository.apply(StateChange.orderOnly(PENDING, PENDING))).verifyComplete();

        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void conflictThatKeepsHappeningShouldSurfaceAfterRetries() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(canceled("TransactionConflict"));

        StepVerifier.create(repository.apply(StateChange.orderOnly(PENDING, PENDING)))
                .expectError(TransactionCanceledException.class)
                .verify();

        verify(client, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void businessConditionFailureShouldNotBeRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("ConditionalCheckFailed", "TransactionConflict"));

        StepVerifier.create(repository.apply(StateChange.orderOnly(PENDING, PENDING)))
                .expectError(ConcurrentUpdateException.class)
                .verify();

        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void otherErrorsShouldPassThrough() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network")));

        StepVerifier.create(repository.apply(RESERVATION)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void cancellationWithoutReasonsShouldPassThrough() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(TransactionCanceledException.builder().message("x").build()));

        StepVerifier.create(repository.apply(RESERVATION)).expectError(TransactionCanceledException.class).verify();
    }
}
