package co.com.ticketing.api.config;

import co.com.ticketing.api.ratelimit.RateLimitFilter;
import co.com.ticketing.api.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("'${app.role:all}' matches 'api|all'")
public class RateLimitConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(ApiProperties properties) {
        return new RateLimitFilter(new RateLimiter(properties.rateLimitCapacity(),
                properties.rateLimitPerSecond(), System::nanoTime));
    }
}
