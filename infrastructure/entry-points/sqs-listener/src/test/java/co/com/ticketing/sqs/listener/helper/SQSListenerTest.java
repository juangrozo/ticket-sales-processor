package co.com.ticketing.sqs.listener.helper;

import co.com.ticketing.sqs.listener.config.SQSProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SQSListenerTest {

    @Mock
    private SqsAsyncClient asyncClient;

    private SQSProperties sqsProperties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        sqsProperties = new SQSProperties(
                "us-east-1",
                "http://localhost:4566",
                "http://localhost:4566/00000000000/queueName",
                20,
                30,
                10,
                1,
                "http://localhost:4566/00000000000/confirmations"
        );

        var message = Message.builder().body("message").build();
        var deleteMessageResponse = DeleteMessageResponse.builder().build();
        var messageResponse = ReceiveMessageResponse.builder().messages(message).build();

        when(asyncClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(messageResponse));
        when(asyncClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(deleteMessageResponse));
    }

    @Test
    void listenerTest() {
        var sqsListener = SQSListener.builder()
                .client(asyncClient)
                .properties(sqsProperties)
                .processor(message -> Mono.empty())
                .operation("operation")
                .build();

        Flux<Void> flow = ReflectionTestUtils.invokeMethod(sqsListener, "listen");
        StepVerifier.create(flow).verifyComplete();
    }

    @Test
    void listenerShouldResumeAfterTheQueueWasUnreachable() {
        var message = Message.builder().body("message").receiptHandle("r-1").build();
        when(asyncClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("connection refused")))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(message).build()))
                // Long poll that never answers, so the loop stops here
                .thenReturn(new CompletableFuture<>());
        var sqsListener = SQSListener.builder()
                .client(asyncClient)
                .properties(sqsProperties)
                .processor(received -> Mono.empty())
                .operation("operation")
                .build();

        StepVerifier.withVirtualTime(() -> ReflectionTestUtils.<Flux<Void>>invokeMethod(sqsListener, "listenRetryRepeat"))
                .thenAwait(SQSListener.RETRY_MAX_BACKOFF)
                .thenCancel()
                .verify();

        verify(asyncClient, times(3)).receiveMessage(any(ReceiveMessageRequest.class));
        verify(asyncClient).deleteMessage(any(DeleteMessageRequest.class));
    }
}
