package co.com.ticketing.model.event;

import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static co.com.ticketing.model.ticket.TicketStatus.AVAILABLE;
import static co.com.ticketing.model.ticket.TicketStatus.COMPLIMENTARY;
import static co.com.ticketing.model.ticket.TicketStatus.PENDING_CONFIRMATION;
import static co.com.ticketing.model.ticket.TicketStatus.RESERVED;
import static co.com.ticketing.model.ticket.TicketStatus.SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    @Test
    void initialInventoryShouldHaveEverythingAvailable() {
        Inventory inventory = Inventory.initial(100);

        assertThat(inventory.available()).isEqualTo(100);
        assertThat(inventory.total()).isEqualTo(100);
    }

    @Test
    void shouldMoveTicketsThroughPurchaseLifecycleKeepingTotal() {
        Inventory inventory = Inventory.initial(100)
                .move("e-1", AVAILABLE, RESERVED, 4)
                .move("e-1", RESERVED, PENDING_CONFIRMATION, 3)
                .move("e-1", PENDING_CONFIRMATION, SOLD, 2)
                .move("e-1", PENDING_CONFIRMATION, AVAILABLE, 1)
                .move("e-1", AVAILABLE, COMPLIMENTARY, 5);

        assertThat(inventory).isEqualTo(new Inventory(92, 1, 0, 2, 5));
        assertThat(inventory.total()).isEqualTo(100);
    }

    @Test
    void complimentaryShouldNotCountForAccounting() {
        Inventory inventory = new Inventory(10, 2, 3, 4, 5);

        assertThat(inventory.accountable()).isEqualTo(4);
        assertThat(inventory.onHold()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void countShouldReturnCounterOfEachStatus(TicketStatus status) {
        Inventory inventory = new Inventory(1, 2, 3, 4, 5);

        assertThat(inventory.count(status)).isEqualTo(status.ordinal() + 1);
    }

    @Test
    void shouldRejectMoveWhenNotEnoughTickets() {
        Inventory inventory = Inventory.initial(2);

        assertThatThrownBy(() -> inventory.move("e-1", AVAILABLE, RESERVED, 3))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    @Test
    void shouldRejectMoveThatBreaksTicketStateMachine() {
        Inventory inventory = new Inventory(0, 0, 0, 1, 0);

        assertThatThrownBy(() -> inventory.move("e-1", SOLD, AVAILABLE, 1))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void shouldRejectNonPositiveQuantity() {
        assertThatThrownBy(() -> Inventory.initial(5).move("e-1", AVAILABLE, RESERVED, 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void shouldRejectNegativeCounters() {
        assertThatThrownBy(() -> new Inventory(-1, 0, 0, 0, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Inventory(0, -1, 0, 0, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Inventory(0, 0, -1, 0, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Inventory(0, 0, 0, -1, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Inventory(0, 0, 0, 0, -1)).isInstanceOf(DomainValidationException.class);
    }
}
