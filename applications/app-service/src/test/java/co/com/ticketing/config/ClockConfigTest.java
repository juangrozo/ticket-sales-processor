package co.com.ticketing.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @Test
    void clockShouldTickInMilliseconds() {
        assertThat(new ClockConfig().clock().instant().getNano() % 1_000_000).isZero();
    }
}
