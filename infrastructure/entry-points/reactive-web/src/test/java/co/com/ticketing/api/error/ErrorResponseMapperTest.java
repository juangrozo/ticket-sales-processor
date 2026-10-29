package co.com.ticketing.api.error;

import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebInputException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseMapperTest {

    @Test
    void shouldMapEveryErrorToItsStatus() {
        assertThat(ErrorResponseMapper.statusOf(new DomainValidationException("x"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorResponseMapper.statusOf(new ResourceNotFoundException("Order", "o-1"))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorResponseMapper.statusOf(new InvalidStateTransitionException("Order", "o-1", "A", "B"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorResponseMapper.statusOf(new InsufficientInventoryException("e-1", 1))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorResponseMapper.statusOf(new ConcurrentUpdateException("Order", "o-1"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorResponseMapper.statusOf(new DuplicateIdempotencyKeyException("k"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorResponseMapper.statusOf(new IdempotencyConflictException())).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorResponseMapper.statusOf(new ReservationExpiredException("o-1"))).isEqualTo(HttpStatus.GONE);
        assertThat(ErrorResponseMapper.statusOf(new ServerWebInputException("bad"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorResponseMapper.statusOf(new DecodingException("bad"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorResponseMapper.statusOf(new IllegalStateException("x"))).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
