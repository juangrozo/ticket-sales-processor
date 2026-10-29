package co.com.ticketing.sqs.sender;

import co.com.ticketing.sqs.sender.config.SQSSenderConfig;
import co.com.ticketing.sqs.sender.config.SQSSenderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.metrics.LoggingMetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSSenderTest {

    private static final SQSSenderProperties PROPERTIES =
            new SQSSenderProperties("us-east-1", "http://localhost:9324/000000000000/order-placed", "http://localhost:9324");

    @Mock
    private SqsAsyncClient client;

    private SQSSender sender;

    @BeforeEach
    void setUp() {
        sender = new SQSSender(PROPERTIES, client);
    }

    @Test
    void shouldSendOrderPlacedMessageAsJson() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m-1").build()));

        StepVerifier.create(sender.publishOrderPlaced("o-1")).verifyComplete();

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(PROPERTIES.queueUrl());
        assertThat(captor.getValue().messageBody()).isEqualTo("{\"orderId\":\"o-1\"}");
    }

    @Test
    void shouldRetryTransientFailures() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("timeout")))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m-1").build()));

        StepVerifier.create(sender.publishOrderPlaced("o-1")).verifyComplete();

        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void shouldFailAfterRetries() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("queue down")));

        StepVerifier.create(sender.publishOrderPlaced("o-1")).expectError(IllegalStateException.class).verify();

        verify(client, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void configShouldBuildClientWithAndWithoutEndpoint() {
        SQSSenderConfig config = new SQSSenderConfig();

        assertThat(config.configSqs(PROPERTIES, LoggingMetricPublisher.create())).isNotNull();
        assertThat(config.configSqs(new SQSSenderProperties("us-east-1", "q", " "), LoggingMetricPublisher.create())).isNotNull();
    }
}
