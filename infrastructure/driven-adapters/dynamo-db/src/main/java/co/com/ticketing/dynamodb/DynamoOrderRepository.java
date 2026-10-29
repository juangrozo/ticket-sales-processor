package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.AuditItemMapper;
import co.com.ticketing.dynamodb.mapper.IdempotencyItemMapper;
import co.com.ticketing.dynamodb.mapper.OrderItemMapper;
import co.com.ticketing.model.audit.AuditEntry;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DynamoOrderRepository implements OrderRepository {

    private static final int IDEMPOTENCY_WRITE = 0;

    private final DynamoDbAsyncClient client;
    private final DynamoDbTables tables;

    /** The idempotency record, the order and its first audit entry are written in one transaction. */
    @Override
    public Mono<Order> create(Order order, IdempotencyRecord idempotencyRecord) {
        return Mono.defer(() -> {
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(
                    putIfAbsent(tables.idempotency(), IdempotencyItemMapper.toItem(idempotencyRecord), IdempotencyItemMapper.KEY),
                    putIfAbsent(tables.orders(), OrderItemMapper.toItem(order), OrderItemMapper.ORDER_ID),
                    TransactWriteItem.builder().put(Put.builder()
                            .tableName(tables.audit())
                            .item(AuditItemMapper.toItem(AuditEntry.orderCreated(order)))
                            .build()).build())
                    .build();
            return Mono.fromFuture(() -> client.transactWriteItems(request))
                    .retryWhen(TransactionSupport.transientRetry())
                    .onErrorMap(TransactionCanceledException.class,
                            error -> TransactionSupport.failedConditionAt(error, IDEMPOTENCY_WRITE)
                                    ? new DuplicateIdempotencyKeyException(idempotencyRecord.key())
                                    : error)
                    .thenReturn(order);
        });
    }

    /** Strongly consistent: the consumer reads the order right after the API wrote it. */
    @Override
    public Mono<Order> findById(String orderId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.orders())
                .key(OrderItemMapper.key(orderId))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> OrderItemMapper.fromItem(response.item()));
    }

    private static TransactWriteItem putIfAbsent(String table, Map<String, AttributeValue> item, String keyAttribute) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(#key)")
                .expressionAttributeNames(Map.of("#key", keyAttribute))
                .build()).build();
    }
}
