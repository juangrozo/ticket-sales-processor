package co.com.ticketing.model.order;

import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.order.OrderTransition.Confirm;
import co.com.ticketing.model.order.OrderTransition.Expire;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Order.RESERVATION_TTL);

    private static Order pending() {
        return Order.place("o-1", "e-1", "c-1", 2, NOW);
    }

    private static Order awaitingConfirmation() {
        return pending().apply(new Reserve(NOW));
    }

    private static Order confirming() {
        return awaitingConfirmation().apply(new RequestConfirmation(NOW.plusSeconds(60)));
    }

    @Test
    void shouldPlacePendingPurchase() {
        Order order = pending();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.type()).isEqualTo(OrderType.PURCHASE);
        assertThat(order.version()).isZero();
        assertThat(order.expiresAt()).isNull();
    }

    @Test
    void shouldCreateConfirmedComplimentaryOrder() {
        Order order = Order.complimentary("o-1", "e-1", "c-1", 3, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.type()).isEqualTo(OrderType.COMPLIMENTARY);
    }

    @Test
    void reserveShouldStartReservationWindow() {
        Order order = awaitingConfirmation();

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_CONFIRMATION);
        assertThat(order.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(order.statusChangedAt()).isEqualTo(NOW);
        assertThat(order.version()).isEqualTo(1);
    }

    @Test
    void shouldCompleteHappyPath() {
        Order order = confirming().apply(new Confirm(NOW.plusSeconds(90)));

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.status().isFinal()).isTrue();
        assertThat(order.version()).isEqualTo(3);
        assertThat(order.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void confirmationRequestShouldFailOnceReservationExpired() {
        Order order = awaitingConfirmation();

        assertThatThrownBy(() -> order.apply(new RequestConfirmation(EXPIRES_AT)))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void shouldRejectPendingOrderWhenNoInventory() {
        Order order = pending().apply(new Reject(NOW, "Insufficient inventory"));

        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.failureReason()).isEqualTo("Insufficient inventory");
    }

    @Test
    void shouldRejectOrderWhenPaymentFails() {
        Order order = confirming().apply(new Reject(NOW.plusSeconds(90), "Payment declined"));

        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void shouldExpireAtTheEndOfTheReservationWindow() {
        Order order = awaitingConfirmation().apply(new Expire(EXPIRES_AT));

        assertThat(order.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.statusChangedAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void shouldNotExpireBeforeTheReservationWindowEnds() {
        Order order = awaitingConfirmation();

        assertThatThrownBy(() -> order.apply(new Expire(EXPIRES_AT.minusMillis(1))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void shouldNotExpireWhilePaymentIsInProgress() {
        Order order = confirming();

        assertThatThrownBy(() -> order.apply(new Expire(EXPIRES_AT.plusSeconds(60))))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CONFIRMING")
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void confirmVsExpireRaceShouldHaveExactlyOneWinner() {
        Order order = awaitingConfirmation();
        Instant atDeadline = EXPIRES_AT;

        assertThat(order.apply(new Expire(atDeadline)).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThatThrownBy(() -> order.apply(new RequestConfirmation(atDeadline)))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void finalOrdersShouldRejectEveryTransition() {
        Order confirmed = confirming().apply(new Confirm(NOW));

        assertThatThrownBy(() -> confirmed.apply(new Reserve(NOW))).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> confirmed.apply(new RequestConfirmation(NOW))).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> confirmed.apply(new Confirm(NOW))).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> confirmed.apply(new Reject(NOW, "x"))).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> confirmed.apply(new Expire(NOW))).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void reserveShouldOnlyApplyToPendingOrders() {
        Order order = awaitingConfirmation();

        assertThatThrownBy(() -> order.apply(new Reserve(NOW)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("AWAITING_CONFIRMATION");
    }

    @Test
    void shouldReportExpiredReservation() {
        Order order = awaitingConfirmation();

        assertThat(order.isReservationExpired(EXPIRES_AT.minusMillis(1))).isFalse();
        assertThat(order.isReservationExpired(EXPIRES_AT)).isTrue();
        assertThat(pending().isReservationExpired(EXPIRES_AT)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Order.MAX_TICKETS_PER_ORDER + 1})
    void shouldRejectInvalidQuantity(int quantity) {
        assertThatThrownBy(() -> Order.place("o-1", "e-1", "c-1", quantity, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void shouldRequireCustomer(String customerId) {
        assertThatThrownBy(() -> Order.place("o-1", "e-1", customerId, 1, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void finalStatusesShouldBeFinal() {
        assertThat(OrderStatus.CONFIRMED.isFinal()).isTrue();
        assertThat(OrderStatus.REJECTED.isFinal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isFinal()).isTrue();
        assertThat(OrderStatus.PENDING.isFinal()).isFalse();
        assertThat(OrderStatus.AWAITING_CONFIRMATION.isFinal()).isFalse();
        assertThat(OrderStatus.CONFIRMING.isFinal()).isFalse();
    }
}
