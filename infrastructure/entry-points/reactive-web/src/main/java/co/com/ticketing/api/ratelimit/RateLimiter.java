package co.com.ticketing.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One token bucket per client. The bucket table is bounded and idle buckets are evicted, so a flood
 * of distinct client ids cannot exhaust memory: the limiter itself is not an attack surface.
 */
public class RateLimiter {

    static final int MAX_TRACKED_CLIENTS = 100_000;
    static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final int capacity;
    private final double tokensPerSecond;
    private final LongSupplier nanoTime;
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterAccess(IDLE_EVICTION)
            .build();

    public RateLimiter(int capacity, double tokensPerSecond, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.tokensPerSecond = tokensPerSecond;
        this.nanoTime = nanoTime;
    }

    /** @return {@link Duration#ZERO} if the request may go ahead, otherwise how long the client must wait */
    public Duration acquire(String clientKey) {
        long now = nanoTime.getAsLong();
        long waitNanos = buckets.get(clientKey, _ -> new TokenBucket(capacity, tokensPerSecond, now)).tryConsume(now);
        return Duration.ofNanos(waitNanos);
    }
}
