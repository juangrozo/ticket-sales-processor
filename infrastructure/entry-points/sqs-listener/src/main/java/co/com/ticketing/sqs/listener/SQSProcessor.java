package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

/**
 * Handles order-placed messages. A failure leaves the message in the queue: it becomes visible
 * again after the visibility timeout and, after five failed attempts, moves to the DLQ.
 */
@Service
@RequiredArgsConstructor
public class SQSProcessor implements Function<Message, Mono<Void>> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReserveTicketsUseCase reserveTicketsUseCase;

    public record OrderPlacedMessage(String orderId) {
    }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.fromCallable(() -> JSON.readValue(message.body(), OrderPlacedMessage.class))
                .filter(payload -> payload.orderId() != null && !payload.orderId().isBlank())
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Message without orderId: " + message.messageId())))
                .flatMap(payload -> reserveTicketsUseCase.reserve(payload.orderId()));
    }
}
