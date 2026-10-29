package co.com.ticketing.usecase.getevent;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Event EVENT = Event.create("e-1", "Rock Fest", "Arena", NOW.plusSeconds(3600), 10, NOW);

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private GetEventUseCase useCase;

    @Test
    void shouldReturnEvent() {
        when(eventRepository.findById("e-1")).thenReturn(Mono.just(EVENT));

        StepVerifier.create(useCase.get("e-1")).expectNext(EVENT).verifyComplete();
    }

    @Test
    void shouldFailWhenEventDoesNotExist() {
        when(eventRepository.findById("e-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.get("e-1")).expectError(ResourceNotFoundException.class).verify();
    }

    @Test
    void shouldListEvents() {
        when(eventRepository.findAll()).thenReturn(Flux.just(EVENT));

        StepVerifier.create(useCase.list()).expectNext(EVENT).verifyComplete();
    }
}
