package co.com.ticketing.model.idempotency.gateways;

import co.com.ticketing.model.idempotency.IdempotencyRecord;
import reactor.core.publisher.Mono;

public interface IdempotencyRepository {

    Mono<IdempotencyRecord> findByKey(String scopedKey);
}
