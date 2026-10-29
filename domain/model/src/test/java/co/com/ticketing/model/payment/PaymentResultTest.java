package co.com.ticketing.model.payment;

import co.com.ticketing.model.payment.PaymentResult.Approved;
import co.com.ticketing.model.payment.PaymentResult.Declined;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentResultTest {

    private static String describe(PaymentResult result) {
        return switch (result) {
            case Approved approved -> "approved " + approved.reference();
            case Declined declined -> "declined " + declined.reason();
        };
    }

    @Test
    void shouldExposeEachOutcome() {
        assertThat(describe(new Approved("ref-1"))).isEqualTo("approved ref-1");
        assertThat(describe(new Declined("insufficient funds"))).isEqualTo("declined insufficient funds");
    }
}
