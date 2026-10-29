package co.com.ticketing.model.payment.gateways;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.payment.PaymentResult;
import reactor.core.publisher.Mono;

public interface PaymentGateway {

    /**
     * Charges the order. Implementations must use the order id as idempotency key, because the
     * confirmation message can be delivered more than once.
     */
    Mono<PaymentResult> charge(Order order);
}
