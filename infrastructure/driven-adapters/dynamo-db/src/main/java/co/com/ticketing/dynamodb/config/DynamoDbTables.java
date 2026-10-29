package co.com.ticketing.dynamodb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.dynamodb.tables")
public record DynamoDbTables(String events, String orders, String tickets, String audit, String idempotency) {
}
