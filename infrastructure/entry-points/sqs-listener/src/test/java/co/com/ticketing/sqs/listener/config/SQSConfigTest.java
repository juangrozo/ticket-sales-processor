package co.com.ticketing.sqs.listener.config;

import co.com.ticketing.sqs.listener.ConfirmationRequestedProcessor;
import co.com.ticketing.sqs.listener.OrderPlacedProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.metrics.LoggingMetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SQSConfigTest {

    @InjectMocks
    private SQSConfig sqsConfig;

    @Mock
    private SqsAsyncClient sqsAsyncClient;

    @Mock
    private SQSProperties sqsProperties;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
        when(sqsProperties.region()).thenReturn("us-east-1");
        when(sqsProperties.queueUrl()).thenReturn("http://localhost:4566/00000000000/queue-sqs");
        when(sqsProperties.waitTimeSeconds()).thenReturn(20);
        when(sqsProperties.maxNumberOfMessages()).thenReturn(10);
        when(sqsProperties.numberOfThreads()).thenReturn(1);
    }

    @Test
    void configSQSListenerIsNotNull() {
        when(sqsProperties.forQueue(any())).thenReturn(sqsProperties);
        assertThat(sqsConfig.orderPlacedListener(sqsAsyncClient, sqsProperties,
                mock(OrderPlacedProcessor.class))).isNotNull();
        assertThat(sqsConfig.confirmationListener(sqsAsyncClient, sqsProperties,
                mock(ConfirmationRequestedProcessor.class))).isNotNull();
    }

    @Test
    void configSqsIsNotNull() {
        var loggingMetricPublisher = LoggingMetricPublisher.create();
        assertThat(sqsConfig.configSqs(sqsProperties, loggingMetricPublisher)).isNotNull();
    }

    @Test
    void configSqsWhenEndpointIsNotNull() {
        var loggingMetricPublisher = LoggingMetricPublisher.create();
        when(sqsProperties.endpoint()).thenReturn("http://localhost:4566");
        assertThat(sqsConfig.configSqs(sqsProperties, loggingMetricPublisher)).isNotNull();
    }

    @Test
    void resolveEndpointIsNull() {
        assertThat(sqsConfig.resolveEndpoint(sqsProperties)).isNull();
    }
}
