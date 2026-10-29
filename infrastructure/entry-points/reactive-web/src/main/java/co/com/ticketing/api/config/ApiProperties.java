package co.com.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param adminApiKey               shared secret for admin endpoints, injected from the environment;
 *                                  blank disables them
 * @param availabilityPollInterval  how often a live availability stream checks for changes
 * @param availabilityMaxDuration   a live stream closes after this, so idle clients do not hold connections
 * @param rateLimitCapacity         burst of requests a client may send at once
 * @param rateLimitPerSecond        sustained requests per second per client
 */
@ConfigurationProperties(prefix = "app.api")
public record ApiProperties(String adminApiKey, Duration availabilityPollInterval, Duration availabilityMaxDuration,
                            int rateLimitCapacity, double rateLimitPerSecond) {
}
