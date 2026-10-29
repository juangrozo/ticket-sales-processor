package co.com.ticketing.sqs.listener;

import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

/**
 * Reads the order id from a message and hands it to the use case. A failure leaves the message in
 * the queue: it becomes visible again after the visibility timeout and, after five failed attempts,
 * moves to the DLQ.
 */
public abstract class OrderMessageProcessor implements Function<Message, Mono<Void>> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record OrderMessage(String orderId) {
    }

    protected abstract Mono<Void> handle(String orderId);

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.fromCallable(() -> JSON.readValue(message.body(), OrderMessage.class))
                .filter(payload -> payload.orderId() != null && !payload.orderId().isBlank())
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Message without orderId: " + message.messageId())))
                .flatMap(payload -> handle(payload.orderId()));
    }
}
