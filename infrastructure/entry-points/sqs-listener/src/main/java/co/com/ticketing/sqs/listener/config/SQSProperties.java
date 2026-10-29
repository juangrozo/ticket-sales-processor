package co.com.ticketing.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entrypoint.sqs")
public record SQSProperties(
        String region,
        String endpoint,
        String queueUrl,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds,
        int maxNumberOfMessages,
        int numberOfThreads,
        String confirmationQueueUrl) {

    /** Same polling settings, another queue. */
    public SQSProperties forQueue(String otherQueueUrl) {
        return new SQSProperties(region, endpoint, otherQueueUrl, waitTimeSeconds, visibilityTimeoutSeconds,
                maxNumberOfMessages, numberOfThreads, confirmationQueueUrl);
    }
}
