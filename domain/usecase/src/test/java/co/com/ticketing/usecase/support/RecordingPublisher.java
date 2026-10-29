package co.com.ticketing.usecase.support;

import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Publisher that records what would be sent instead of sending it. */
public class RecordingPublisher implements OrderEventPublisher {

    private final List<String> placed = new CopyOnWriteArrayList<>();
    private final List<String> confirmations = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> publishOrderPlaced(String orderId) {
        return Mono.fromRunnable(() -> placed.add(orderId));
    }

    @Override
    public Mono<Void> publishConfirmationRequested(String orderId) {
        return Mono.fromRunnable(() -> confirmations.add(orderId));
    }

    public List<String> placed() {
        return List.copyOf(placed);
    }

    public List<String> confirmations() {
        return List.copyOf(confirmations);
    }
}
