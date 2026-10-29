package co.com.ticketing.model.ticket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static co.com.ticketing.model.ticket.TicketStatus.AVAILABLE;
import static co.com.ticketing.model.ticket.TicketStatus.COMPLIMENTARY;
import static co.com.ticketing.model.ticket.TicketStatus.SOLD;
import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "AVAILABLE, RESERVED, true",
            "AVAILABLE, COMPLIMENTARY, true",
            "AVAILABLE, PENDING_CONFIRMATION, false",
            "AVAILABLE, SOLD, false",
            "AVAILABLE, AVAILABLE, false",
            "RESERVED, PENDING_CONFIRMATION, true",
            "RESERVED, AVAILABLE, true",
            "RESERVED, SOLD, false",
            "RESERVED, COMPLIMENTARY, false",
            "PENDING_CONFIRMATION, SOLD, true",
            "PENDING_CONFIRMATION, AVAILABLE, true",
            "PENDING_CONFIRMATION, RESERVED, false",
            "PENDING_CONFIRMATION, COMPLIMENTARY, false"
    })
    void shouldAllowOnlyValidTransitions(TicketStatus from, TicketStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"SOLD", "COMPLIMENTARY"})
    void finalStatesShouldNotTransition(TicketStatus finalStatus) {
        assertThat(finalStatus.isFinal()).isTrue();
        for (TicketStatus target : TicketStatus.values()) {
            assertThat(finalStatus.canTransitionTo(target)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"AVAILABLE", "RESERVED", "PENDING_CONFIRMATION"})
    void nonFinalStatesShouldNotBeFinal(TicketStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void onlyReservedAndPendingShouldBeOnHold(TicketStatus status) {
        assertThat(status.isOnHold())
                .isEqualTo(status == TicketStatus.RESERVED || status == TicketStatus.PENDING_CONFIRMATION);
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void onlySoldShouldBeAccountable(TicketStatus status) {
        assertThat(status.isAccountable()).isEqualTo(status == SOLD);
        assertThat(COMPLIMENTARY.isAccountable()).isFalse();
        assertThat(AVAILABLE.isAccountable()).isFalse();
    }
}
