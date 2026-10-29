package co.com.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Single time source, injected so tests can fix the time instead of sleeping. It ticks in
 * milliseconds, the precision DynamoDB stores, so a value reads back exactly as it was written.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
