package co.com.ticketing.model.idempotency;

import co.com.ticketing.model.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void shouldExpireAfterTtl() {
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW);

        assertThat(record.expiresAt()).isEqualTo(NOW.plus(IdempotencyRecord.TTL));
    }

    @Test
    void shouldScopeKeyByCustomer() {
        assertThat(IdempotencyRecord.scopedKey("c-1", "key-1")).isEqualTo("c-1#key-1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRequireKey(String key) {
        assertThatThrownBy(() -> IdempotencyRecord.scopedKey("c-1", key))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void shouldRejectTooLongKey() {
        String key = "k".repeat(IdempotencyRecord.MAX_KEY_LENGTH + 1);

        assertThatThrownBy(() -> IdempotencyRecord.scopedKey("c-1", key))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void hashShouldBeStableAndSensitiveToPayload() {
        String hash = IdempotencyRecord.hashOf("e-1", "c-1", 2);

        assertThat(hash).hasSize(64).isEqualTo(IdempotencyRecord.hashOf("e-1", "c-1", 2));
        assertThat(hash).isNotEqualTo(IdempotencyRecord.hashOf("e-1", "c-1", 3));
        assertThat(hash).isNotEqualTo(IdempotencyRecord.hashOf("e-2", "c-1", 2));
    }

    @Test
    void shouldMatchOnlySameHash() {
        IdempotencyRecord record = IdempotencyRecord.of("c-1#k", "hash", "o-1", NOW);

        assertThat(record.matches("hash")).isTrue();
        assertThat(record.matches("other")).isFalse();
    }

    @Test
    void shouldRequireFields() {
        assertThatThrownBy(() -> new IdempotencyRecord("k", "h", null, NOW)).isInstanceOf(NullPointerException.class);
    }
}
