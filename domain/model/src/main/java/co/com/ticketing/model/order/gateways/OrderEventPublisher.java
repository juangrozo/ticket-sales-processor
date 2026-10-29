package co.com.ticketing.model.order.gateways;

import reactor.core.publisher.Mono;

public interface OrderEventPublisher {

    Mono<Void> publishOrderPlaced(String orderId);
}
