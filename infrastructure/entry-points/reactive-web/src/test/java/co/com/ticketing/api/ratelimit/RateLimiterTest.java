package co.com.ticketing.api.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    // Bursts of 3, then 2 requests per second
    private final RateLimiter limiter = new RateLimiter(3, 2, now::get);

    @Test
    void shouldAllowBurstUpToCapacityThenAskToWait() {
        assertThat(limiter.acquire("c-1")).isZero();
        assertThat(limiter.acquire("c-1")).isZero();
        assertThat(limiter.acquire("c-1")).isZero();

        assertThat(limiter.acquire("c-1")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void shouldRefillOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("c-1");
        }

        now.addAndGet(Duration.ofMillis(500).toNanos());

        assertThat(limiter.acquire("c-1")).isZero();
        assertThat(limiter.acquire("c-1")).isPositive();
    }

    @Test
    void shouldNeverRefillAboveCapacity() {
        now.addAndGet(Duration.ofHours(1).toNanos());

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("c-1")).isZero();
        }
        assertThat(limiter.acquire("c-1")).isPositive();
    }

    @Test
    void clientsShouldNotShareBuckets() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("c-1");
        }

        assertThat(limiter.acquire("c-1")).isPositive();
        assertThat(limiter.acquire("c-2")).isZero();
    }
}
