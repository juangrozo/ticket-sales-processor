package co.com.ticketing.model.change.gateways;

import co.com.ticketing.model.change.StateChange;
import reactor.core.publisher.Mono;

public interface StateChangeRepository {

    /**
     * Applies the whole change atomically. Fails with {@code InsufficientInventoryException} when the
     * inventory move is not possible, or {@code ConcurrentUpdateException} when the order or a ticket
     * is no longer in the expected state.
     */
    Mono<Void> apply(StateChange change);
}
