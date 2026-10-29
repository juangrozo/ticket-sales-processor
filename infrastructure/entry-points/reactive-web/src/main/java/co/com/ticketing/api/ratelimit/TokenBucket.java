package co.com.ticketing.api.ratelimit;

/**
 * Classic token bucket: holds up to {@code capacity} tokens and refills continuously. Each request
 * takes one token; bursts are allowed up to the capacity, the sustained rate is the refill rate.
 * The lock only guards a few arithmetic operations, never I/O.
 */
final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(int capacity, double tokensPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.tokensPerNano = tokensPerSecond / 1_000_000_000d;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    /** @return 0 if a token was taken, otherwise the nanoseconds until one is available */
    synchronized long tryConsume(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / tokensPerNano);
    }
}
