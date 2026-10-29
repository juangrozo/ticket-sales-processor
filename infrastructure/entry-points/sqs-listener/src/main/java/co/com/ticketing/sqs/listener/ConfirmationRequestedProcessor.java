package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.processpayment.ProcessPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** order-confirmation queue: charges the order and settles its tickets. */
@Service
@RequiredArgsConstructor
public class ConfirmationRequestedProcessor extends OrderMessageProcessor {

    private final ProcessPaymentUseCase processPaymentUseCase;

    @Override
    protected Mono<Void> handle(String orderId) {
        return processPaymentUseCase.process(orderId);
    }
}
