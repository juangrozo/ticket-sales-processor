package co.com.ticketing.sqs.sender;

import co.com.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.ticketing.sqs.sender.config.SQSSenderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Service
@Log4j2
@RequiredArgsConstructor
public class SQSSender implements OrderEventPublisher {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SQSSenderProperties properties;
    private final SqsAsyncClient client;

    public record OrderPlacedMessage(String orderId) {
    }

    @Override
    public Mono<Void> publishOrderPlaced(String orderId) {
        return Mono.fromCallable(() -> buildRequest(JSON.writeValueAsString(new OrderPlacedMessage(orderId))))
                .flatMap(request -> Mono.fromFuture(() -> client.sendMessage(request)))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100)).onRetryExhaustedThrow((_, signal) -> signal.failure()))
                .doOnNext(response -> log.debug("Order {} queued as message {}", orderId, response.messageId()))
                .then();
    }

    private SendMessageRequest buildRequest(String message) {
        return SendMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .messageBody(message)
                .build();
    }
}
