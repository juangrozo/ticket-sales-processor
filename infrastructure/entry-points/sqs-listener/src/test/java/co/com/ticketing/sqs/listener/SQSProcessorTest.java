package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSProcessorTest {

    @Mock
    private ReserveTicketsUseCase reserveTicketsUseCase;

    @InjectMocks
    private SQSProcessor processor;

    @Test
    void shouldReserveTicketsOfTheOrder() {
        when(reserveTicketsUseCase.reserve("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(processor.apply(Message.builder().body("{\"orderId\":\"o-1\"}").build())).verifyComplete();

        verify(reserveTicketsUseCase).reserve("o-1");
    }

    @Test
    void malformedMessageShouldFailSoItEndsInTheDlq() {
        StepVerifier.create(processor.apply(Message.builder().body("not json").build())).expectError().verify();

        verify(reserveTicketsUseCase, never()).reserve(anyString());
    }

    @Test
    void messageWithoutOrderIdShouldFail() {
        StepVerifier.create(processor.apply(Message.builder().messageId("m-1").body("{}").build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void useCaseFailureShouldPropagateSoTheMessageIsRetried() {
        when(reserveTicketsUseCase.reserve("o-1")).thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(processor.apply(Message.builder().body("{\"orderId\":\"o-1\"}").build()))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
