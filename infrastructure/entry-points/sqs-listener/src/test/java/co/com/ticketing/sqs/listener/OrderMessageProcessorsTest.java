package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.processpayment.ProcessPaymentUseCase;
import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class OrderMessageProcessorsTest {

    private static final Message MESSAGE = Message.builder().body("{\"orderId\":\"o-1\"}").build();

    @Mock
    private ReserveTicketsUseCase reserveTicketsUseCase;
    @Mock
    private ProcessPaymentUseCase processPaymentUseCase;

    @Test
    void orderPlacedShouldReserveTickets() {
        when(reserveTicketsUseCase.reserve("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(new OrderPlacedProcessor(reserveTicketsUseCase).apply(MESSAGE)).verifyComplete();

        verify(reserveTicketsUseCase).reserve("o-1");
    }

    @Test
    void confirmationShouldProcessPayment() {
        when(processPaymentUseCase.process("o-1")).thenReturn(Mono.empty());

        StepVerifier.create(new ConfirmationRequestedProcessor(processPaymentUseCase).apply(MESSAGE)).verifyComplete();

        verify(processPaymentUseCase).process("o-1");
    }

    @Test
    void malformedMessageShouldFailSoItEndsInTheDlq() {
        StepVerifier.create(new OrderPlacedProcessor(reserveTicketsUseCase).apply(Message.builder().body("not json").build()))
                .expectError()
                .verify();

        verify(reserveTicketsUseCase, never()).reserve(anyString());
    }

    @Test
    void messageWithoutOrderIdShouldFail() {
        StepVerifier.create(new ConfirmationRequestedProcessor(processPaymentUseCase)
                        .apply(Message.builder().messageId("m-1").body("{}").build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void useCaseFailureShouldPropagateSoTheMessageIsRetried() {
        when(reserveTicketsUseCase.reserve("o-1")).thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(new OrderPlacedProcessor(reserveTicketsUseCase).apply(MESSAGE))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
