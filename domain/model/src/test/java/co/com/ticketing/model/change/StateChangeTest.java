package co.com.ticketing.model.change;

import co.com.ticketing.model.audit.AuditEntry;
import co.com.ticketing.model.change.StateChange.InventoryMove;
import co.com.ticketing.model.change.StateChange.TicketChange;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition.Reject;
import co.com.ticketing.model.order.OrderTransition.RequestConfirmation;
import co.com.ticketing.model.order.OrderTransition.Reserve;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateChangeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Order PENDING = Order.place("o-1", "e-1", "c-1", 2, NOW);

    @Test
    void reservationShouldMoveAvailableToReservedAndAuditEverything() {
        Order reserved = PENDING.apply(new Reserve(NOW));
        List<Ticket> tickets = List.of(
                Ticket.reserved("t-1", "o-1", "e-1", NOW),
                Ticket.reserved("t-2", "o-1", "e-1", NOW));

        StateChange change = StateChange.reservation(PENDING, reserved, tickets);

        assertThat(change.inventoryMove())
                .isEqualTo(new InventoryMove("e-1", TicketStatus.AVAILABLE, TicketStatus.RESERVED, 2));
        assertThat(change.auditEntries())
                .extracting(AuditEntry::entityId)
                .containsExactly("o-1", "t-1", "t-2");
    }

    @Test
    void orderOnlyChangeShouldNotTouchInventory() {
        StateChange change = StateChange.orderOnly(PENDING, PENDING.apply(new Reject(NOW, "Insufficient inventory")));

        assertThat(change.inventoryMove()).isNull();
        assertThat(change.createdTickets()).isEmpty();
        assertThat(change.auditEntries()).singleElement()
                .extracting(AuditEntry::toStatus).isEqualTo("REJECTED");
    }

    @Test
    void shouldAuditChangedTickets() {
        Ticket before = Ticket.reserved("t-1", "o-1", "e-1", NOW);
        Ticket after = before.transitionTo(TicketStatus.PENDING_CONFIRMATION, NOW);

        StateChange change = new StateChange(PENDING, PENDING, null, List.of(), List.of(new TicketChange(before, after)));

        assertThat(change.auditEntries()).hasSize(2)
                .last().extracting(AuditEntry::fromStatus).isEqualTo("RESERVED");
    }

    @Test
    void shouldRejectChangeAcrossOrders() {
        Order other = Order.place("o-2", "e-1", "c-1", 2, NOW);

        assertThatThrownBy(() -> StateChange.orderOnly(PENDING, other))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void shouldRequireOrders() {
        assertThatThrownBy(() -> new StateChange(PENDING, null, null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StateChange.orderOnly(null, PENDING))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void complimentaryShouldCreateOrderAndTakeTicketsFromAvailable() {
        Order grant = Order.complimentary("o-9", "e-1", "vip-1", 2, NOW);
        List<Ticket> tickets = List.of(
                Ticket.complimentary("t-1", "o-9", "e-1", NOW),
                Ticket.complimentary("t-2", "o-9", "e-1", NOW));

        StateChange change = StateChange.complimentary(grant, tickets);

        assertThat(change.createsOrder()).isTrue();
        assertThat(change.inventoryMove())
                .isEqualTo(new InventoryMove("e-1", TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, 2));
        assertThat(change.auditEntries()).first()
                .isEqualTo(AuditEntry.orderCreated(grant));
        assertThat(change.auditEntries()).hasSize(3);
    }

    @Test
    void transitionShouldMoveOrderTicketsAndCountersTogether() {
        Order reserved = PENDING.apply(new Reserve(NOW));
        Order confirming = reserved.apply(new RequestConfirmation(NOW.plusSeconds(30)));
        List<Ticket> tickets = List.of(
                Ticket.reserved("t-1", "o-1", "e-1", NOW),
                Ticket.reserved("t-2", "o-1", "e-1", NOW));

        StateChange change = StateChange.transition(reserved, confirming, tickets,
                TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION);

        assertThat(change.createsOrder()).isFalse();
        assertThat(change.inventoryMove())
                .isEqualTo(new InventoryMove("e-1", TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, 2));
        assertThat(change.changedTickets()).extracting(c -> c.after().status())
                .containsOnly(TicketStatus.PENDING_CONFIRMATION);
        assertThat(change.changedTickets()).extracting(c -> c.after().statusChangedAt())
                .containsOnly(NOW.plusSeconds(30));
        assertThat(change.auditEntries()).hasSize(3);
    }

    @Test
    void transitionShouldRejectTicketsInAnotherState() {
        Order reserved = PENDING.apply(new Reserve(NOW));
        List<Ticket> tickets = List.of(new Ticket("t-1", "o-1", "e-1", TicketStatus.SOLD, NOW));

        assertThatThrownBy(() -> StateChange.transition(reserved, reserved, tickets,
                TicketStatus.RESERVED, TicketStatus.AVAILABLE))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("expected RESERVED");
    }
}
