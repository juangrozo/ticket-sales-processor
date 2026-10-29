package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.AuditItemMapper;
import co.com.ticketing.dynamodb.mapper.EventItemMapper;
import co.com.ticketing.dynamodb.mapper.OrderItemMapper;
import co.com.ticketing.dynamodb.mapper.TicketItemMapper;
import co.com.ticketing.model.change.StateChange;
import co.com.ticketing.model.change.StateChange.InventoryMove;
import co.com.ticketing.model.change.gateways.StateChangeRepository;
import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a {@link StateChange} into one {@code TransactWriteItems} call: either every write
 * happens or none does. Each write carries the condition that protects it, and a failed condition
 * is mapped back to the business error it means.
 */
@Repository
@RequiredArgsConstructor
public class DynamoStateChangeRepository implements StateChangeRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbTables tables;

    private enum Target { ORDER, INVENTORY, TICKET, AUDIT }

    private record Write(Target target, TransactWriteItem item) {
    }

    @Override
    public Mono<Void> apply(StateChange change) {
        return Mono.defer(() -> {
            List<Write> writes = writesFor(change);
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(writes.stream().map(Write::item).toList())
                    .build();
            return Mono.fromFuture(() -> client.transactWriteItems(request))
                    .retryWhen(TransactionSupport.transientRetry())
                    .onErrorMap(TransactionCanceledException.class, error -> toDomainError(error, writes, change))
                    .then();
        });
    }

    private List<Write> writesFor(StateChange change) {
        List<Write> writes = new ArrayList<>();
        writes.add(new Write(Target.ORDER, orderWrite(change.orderBefore(), change.orderAfter())));
        if (change.inventoryMove() != null) {
            writes.add(new Write(Target.INVENTORY, inventoryWrite(change.inventoryMove())));
        }
        change.createdTickets().forEach(ticket -> writes.add(new Write(Target.TICKET, ticketCreation(ticket))));
        change.changedTickets().forEach(ticketChange ->
                writes.add(new Write(Target.TICKET, ticketUpdate(ticketChange.before(), ticketChange.after()))));
        change.auditEntries().forEach(entry -> writes.add(new Write(Target.AUDIT, TransactWriteItem.builder()
                .put(Put.builder().tableName(tables.audit()).item(AuditItemMapper.toItem(entry)).build())
                .build())));
        return writes;
    }

    /**
     * Optimistic locking: the order is replaced only if nobody changed it since it was read. A new
     * order is written only if its id is not taken.
     */
    private TransactWriteItem orderWrite(Order before, Order after) {
        if (before == null) {
            return TransactWriteItem.builder().put(Put.builder()
                    .tableName(tables.orders())
                    .item(OrderItemMapper.toItem(after))
                    .conditionExpression("attribute_not_exists(#orderId)")
                    .expressionAttributeNames(Map.of("#orderId", OrderItemMapper.ORDER_ID))
                    .build()).build();
        }
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.orders())
                .item(OrderItemMapper.toItem(after))
                .conditionExpression("#version = :expectedVersion AND #status = :expectedStatus")
                .expressionAttributeNames(Map.of(
                        "#version", OrderItemMapper.VERSION,
                        "#status", OrderItemMapper.ORDER_STATUS))
                .expressionAttributeValues(Map.of(
                        ":expectedVersion", AttributeValue.fromN(Long.toString(before.version())),
                        ":expectedStatus", AttributeValue.fromS(before.status().name())))
                .build()).build();
    }

    /** Atomic counter move: no read-modify-write, the condition is evaluated by DynamoDB itself. */
    private TransactWriteItem inventoryWrite(InventoryMove move) {
        String quantity = Integer.toString(move.quantity());
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tables.events())
                .key(EventItemMapper.key(move.eventId()))
                .updateExpression("ADD #from :decrement, #to :increment")
                .conditionExpression("attribute_exists(#eventId) AND #from >= :quantity")
                .expressionAttributeNames(Map.of(
                        "#eventId", EventItemMapper.EVENT_ID,
                        "#from", EventItemMapper.counterOf(move.from()),
                        "#to", EventItemMapper.counterOf(move.to())))
                .expressionAttributeValues(Map.of(
                        ":decrement", AttributeValue.fromN("-" + quantity),
                        ":increment", AttributeValue.fromN(quantity),
                        ":quantity", AttributeValue.fromN(quantity)))
                .build()).build();
    }

    private TransactWriteItem ticketCreation(Ticket ticket) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.tickets())
                .item(TicketItemMapper.toItem(ticket))
                .conditionExpression("attribute_not_exists(#ticketId)")
                .expressionAttributeNames(Map.of("#ticketId", TicketItemMapper.TICKET_ID))
                .build()).build();
    }

    private TransactWriteItem ticketUpdate(Ticket before, Ticket after) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.tickets())
                .item(TicketItemMapper.toItem(after))
                .conditionExpression("#status = :expectedStatus")
                .expressionAttributeNames(Map.of("#status", TicketItemMapper.TICKET_STATUS))
                .expressionAttributeValues(Map.of(":expectedStatus", AttributeValue.fromS(before.status().name())))
                .build()).build();
    }

    private Throwable toDomainError(TransactionCanceledException error, List<Write> writes, StateChange change) {
        for (int i = 0; i < writes.size(); i++) {
            if (TransactionSupport.failedConditionAt(error, i)) {
                return switch (writes.get(i).target()) {
                    case ORDER -> new ConcurrentUpdateException("Order", change.orderAfter().orderId());
                    case INVENTORY -> new InsufficientInventoryException(
                            change.inventoryMove().eventId(), change.inventoryMove().quantity());
                    case TICKET, AUDIT -> new ConcurrentUpdateException("Ticket of order", change.orderAfter().orderId());
                };
            }
        }
        return error;
    }
}
