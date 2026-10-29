package co.com.ticketing.api.error;

import co.com.ticketing.model.exception.ConcurrentUpdateException;
import co.com.ticketing.model.exception.DomainException;
import co.com.ticketing.model.exception.DomainValidationException;
import co.com.ticketing.model.exception.DuplicateIdempotencyKeyException;
import co.com.ticketing.model.exception.IdempotencyConflictException;
import co.com.ticketing.model.exception.InsufficientInventoryException;
import co.com.ticketing.model.exception.InvalidStateTransitionException;
import co.com.ticketing.model.exception.ReservationExpiredException;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Turns any error into an RFC 9457 problem response. Internal details never reach the client. */
@Log4j2
@Component
public class ErrorResponseMapper {

    public Mono<ServerResponse> toResponse(Throwable error, ServerRequest request) {
        HttpStatus status = statusOf(error);
        String detail = switch (error) {
            case Throwable _ when status.is5xxServerError() -> "Unexpected error";
            case ResponseStatusException statusError -> statusError.getReason();
            default -> error.getMessage();
        };
        if (status.is5xxServerError()) {
            log.error("Unexpected error on {} {}", request.method(), request.path(), error);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.path()));
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem);
    }

    static HttpStatus statusOf(Throwable error) {
        return switch (error) {
            case DomainException domainError -> statusOf(domainError);
            case ServerWebInputException _, CodecException _ -> HttpStatus.BAD_REQUEST;
            case ResponseStatusException statusError -> HttpStatus.valueOf(statusError.getStatusCode().value());
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** Exhaustive over the sealed hierarchy: a new business error does not compile until it is mapped. */
    private static HttpStatus statusOf(DomainException error) {
        return switch (error) {
            case DomainValidationException _ -> HttpStatus.BAD_REQUEST;
            case ResourceNotFoundException _ -> HttpStatus.NOT_FOUND;
            case InvalidStateTransitionException _, InsufficientInventoryException _, ConcurrentUpdateException _,
                 DuplicateIdempotencyKeyException _ -> HttpStatus.CONFLICT;
            // Same key, different payload: the request itself is wrong, retrying it will never work
            case IdempotencyConflictException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case ReservationExpiredException _ -> HttpStatus.GONE;
        };
    }
}
