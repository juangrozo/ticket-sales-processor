package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.EventItemMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DynamoEventRepository implements EventRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbTables tables;

    @Override
    public Mono<Event> save(Event event) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tables.events())
                .item(EventItemMapper.toItem(event))
                .conditionExpression("attribute_not_exists(#eventId)")
                .expressionAttributeNames(Map.of("#eventId", EventItemMapper.EVENT_ID))
                .build();
        return Mono.fromFuture(() -> client.putItem(request)).thenReturn(event);
    }

    @Override
    public Mono<Event> findById(String eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.events())
                .key(EventItemMapper.key(eventId))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> EventItemMapper.fromItem(response.item()));
    }

    /** The events table is small (one item per event), so a paginated Scan is acceptable here. */
    @Override
    public Flux<Event> findAll() {
        return Flux.from(client.scanPaginator(ScanRequest.builder().tableName(tables.events()).build()).items())
                .map(EventItemMapper::fromItem);
    }
}
