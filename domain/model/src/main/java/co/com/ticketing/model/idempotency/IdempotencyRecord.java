package co.com.ticketing.model.idempotency;

import co.com.ticketing.model.exception.DomainValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Links a client idempotency key to the order it created. Keys are scoped per customer so one
 * client cannot collide with, or probe, another client's keys.
 *
 * @param requestHash fingerprint of the original payload, to detect a key reused for a different request
 */
public record IdempotencyRecord(String key, String requestHash, String orderId, Instant expiresAt) {

    public static final Duration TTL = Duration.ofHours(24);
    public static final int MAX_KEY_LENGTH = 128;

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static IdempotencyRecord of(String scopedKey, String requestHash, String orderId, Instant now) {
        return new IdempotencyRecord(scopedKey, requestHash, orderId, now.plus(TTL));
    }

    public static String scopedKey(String customerId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainValidationException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new DomainValidationException("Idempotency-Key must have at most " + MAX_KEY_LENGTH + " characters");
        }
        return customerId + "#" + idempotencyKey;
    }

    public static String hashOf(String eventId, String customerId, int quantity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest("%s|%s|%d".formatted(eventId, customerId, quantity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java platform implementation
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }
}
