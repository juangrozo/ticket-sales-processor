package co.com.ticketing.usecase.createevent;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-12-15T20:00:00Z");

    @Mock
    private EventRepository eventRepository;

    private CreateEventUseCase useCase() {
        return new CreateEventUseCase(eventRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateEventWithFullInventory() {
        when(eventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(useCase().create("Rock Fest", "Arena", STARTS_AT, 100))
                .assertNext(event -> {
                    assertThat(event.eventId()).isNotBlank();
                    assertThat(event.inventory().available()).isEqualTo(100);
                    assertThat(event.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotSaveInvalidEvent() {
        StepVerifier.create(useCase().create("Rock Fest", "Arena", STARTS_AT, 0))
                .expectError(DomainValidationException.class)
                .verify();

        verify(eventRepository, never()).save(any(Event.class));
    }
}
