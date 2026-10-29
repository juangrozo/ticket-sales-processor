package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.TicketItemMapper;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DynamoTicketRepository implements TicketRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbTables tables;

    @Override
    public Flux<Ticket> findByOrderId(String orderId) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tables.tickets())
                .keyConditionExpression("#orderId = :orderId")
                .expressionAttributeNames(Map.of("#orderId", TicketItemMapper.ORDER_ID))
                .expressionAttributeValues(Map.of(":orderId", AttributeValue.fromS(orderId)))
                .consistentRead(true)
                .build();
        return Flux.from(client.queryPaginator(request).items()).map(TicketItemMapper::fromItem);
    }
}
