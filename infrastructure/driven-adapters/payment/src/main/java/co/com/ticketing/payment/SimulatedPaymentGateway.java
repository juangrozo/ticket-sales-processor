package co.com.ticketing.payment;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.model.payment.PaymentResult.Approved;
import co.com.ticketing.model.payment.PaymentResult.Declined;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Stand-in for a real payment provider. Approves every order except those of customers whose id
 * starts with {@value #DECLINED_CUSTOMER_PREFIX}, which lets the decline path be exercised end to end.
 * The latency is simulated with a timer, never by blocking a thread.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    public static final String DECLINED_CUSTOMER_PREFIX = "declined-";
    static final Duration LATENCY = Duration.ofMillis(150);

    @Override
    public Mono<PaymentResult> charge(Order order) {
        PaymentResult result = order.customerId().startsWith(DECLINED_CUSTOMER_PREFIX)
                ? new Declined("card declined")
                // The order id doubles as idempotency key, as a real provider would require
                : new Approved("sim-" + order.orderId());
        return Mono.delay(LATENCY).thenReturn(result);
    }
}
