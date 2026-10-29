package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbTables;
import co.com.ticketing.dynamodb.mapper.IdempotencyItemMapper;
import co.com.ticketing.model.idempotency.IdempotencyRecord;
import co.com.ticketing.model.idempotency.gateways.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Clock;

@Repository
@RequiredArgsConstructor
public class DynamoIdempotencyRepository implements IdempotencyRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbTables tables;
    private final Clock clock;

    /** TTL deletion can lag for hours, so records past their expiry are ignored explicitly. */
    @Override
    public Mono<IdempotencyRecord> findByKey(String scopedKey) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.idempotency())
                .key(IdempotencyItemMapper.key(scopedKey))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> IdempotencyItemMapper.fromItem(response.item()))
                .filter(record -> record.expiresAt().isAfter(clock.instant()));
    }
}
