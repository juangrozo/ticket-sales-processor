package co.com.ticketing.payment;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.payment.PaymentResult.Approved;
import co.com.ticketing.model.payment.PaymentResult.Declined;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;

class SimulatedPaymentGatewayTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void shouldApproveRegularCustomers() {
        StepVerifier.withVirtualTime(() -> gateway.charge(Order.place("o-1", "e-1", "c-1", 1, NOW)))
                .thenAwait(SimulatedPaymentGateway.LATENCY)
                .expectNext(new Approved("sim-o-1"))
                .verifyComplete();
    }

    @Test
    void shouldDeclineTestCustomers() {
        Order order = Order.place("o-2", "e-1", SimulatedPaymentGateway.DECLINED_CUSTOMER_PREFIX + "1", 1, NOW);

        StepVerifier.withVirtualTime(() -> gateway.charge(order))
                .thenAwait(SimulatedPaymentGateway.LATENCY)
                .expectNext(new Declined("card declined"))
                .verifyComplete();
    }
}
