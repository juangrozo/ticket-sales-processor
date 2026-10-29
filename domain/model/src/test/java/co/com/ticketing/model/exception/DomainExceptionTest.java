package co.com.ticketing.model.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionTest {

    @Test
    void shouldBuildDescriptiveMessages() {
        assertThat(new ResourceNotFoundException("Order", "o-1")).hasMessage("Order o-1 not found");
        assertThat(new ReservationExpiredException("o-1")).hasMessage("Reservation for order o-1 has expired");
        assertThat(new InvalidStateTransitionException("Order", "o-1", "PENDING", "CONFIRMED"))
                .hasMessage("Order o-1 cannot move from PENDING to CONFIRMED");
        assertThat(new InsufficientInventoryException("e-1", 5, 2))
                .hasMessage("Event e-1 has 2 tickets in the requested state, 5 requested");
        assertThat(new DomainValidationException("bad")).hasMessage("bad");
        assertThat(new InsufficientInventoryException("e-1", 3))
                .hasMessage("Event e-1 does not have 3 tickets in the requested state");
        assertThat(new ConcurrentUpdateException("Order", "o-1")).hasMessage("Order o-1 was modified concurrently");
        assertThat(new IdempotencyConflictException()).hasMessageContaining("different request");
        assertThat(new DuplicateIdempotencyKeyException("c-1#k")).hasMessageContaining("c-1#k");
    }

    @Test
    void allBusinessErrorsShouldShareTheSealedRoot() {
        DomainException error = new ResourceNotFoundException("Event", "e-1");

        int status = switch (error) {
            case DomainValidationException _ -> 400;
            case ResourceNotFoundException _ -> 404;
            case InvalidStateTransitionException _, InsufficientInventoryException _, ConcurrentUpdateException _,
                 IdempotencyConflictException _, DuplicateIdempotencyKeyException _ -> 409;
            case ReservationExpiredException _ -> 410;
        };

        assertThat(status).isEqualTo(404);
    }
}
