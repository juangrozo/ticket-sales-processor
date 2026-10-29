package co.com.ticketing.sqs.listener.config;

import co.com.ticketing.sqs.listener.ConfirmationRequestedProcessor;
import co.com.ticketing.sqs.listener.OrderPlacedProcessor;
import co.com.ticketing.sqs.listener.helper.SQSListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
import java.util.function.Function;

@Configuration
public class SQSConfig {

    /** Consumes order-placed only in the {@code worker} role (or {@code all}). */
    @Bean
    @ConditionalOnExpression("'${app.role:all}' matches 'worker|all'")
    public SQSListener orderPlacedListener(SqsAsyncClient client, SQSProperties properties,
                                           OrderPlacedProcessor processor) {
        return listener(client, properties, processor);
    }

    /** Consumes order-confirmation only in the {@code worker} role (or {@code all}). */
    @Bean
    @ConditionalOnExpression("'${app.role:all}' matches 'worker|all'")
    public SQSListener confirmationListener(SqsAsyncClient client, SQSProperties properties,
                                            ConfirmationRequestedProcessor processor) {
        return listener(client, properties.forQueue(properties.confirmationQueueUrl()), processor);
    }

    private static SQSListener listener(SqsAsyncClient client, SQSProperties properties,
                                        Function<Message, Mono<Void>> processor) {
        return SQSListener.builder()
                .client(client)
                .properties(properties)
                .processor(processor)
                .build()
                .start();
    }

    @Bean
    public SqsAsyncClient configSqs(SQSProperties properties, MetricPublisher publisher) {
        return SqsAsyncClient.builder()
                .endpointOverride(resolveEndpoint(properties))
                .region(Region.of(properties.region()))
                .overrideConfiguration(o -> o.addMetricPublisher(publisher))
                .credentialsProvider(getProviderChain())
                .build();
    }

    private AwsCredentialsProviderChain getProviderChain() {
        return AwsCredentialsProviderChain.builder()
                .addCredentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .addCredentialsProvider(SystemPropertyCredentialsProvider.create())
                .addCredentialsProvider(WebIdentityTokenFileCredentialsProvider.create())
                .addCredentialsProvider(ProfileCredentialsProvider.create())
                .addCredentialsProvider(ContainerCredentialsProvider.builder().build())
                .addCredentialsProvider(InstanceProfileCredentialsProvider.create())
                .build();
    }

    protected URI resolveEndpoint(SQSProperties properties) {
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            return URI.create(properties.endpoint());
        }
        return null;
    }
}
