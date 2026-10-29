package co.com.ticketing.integration;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * The only blocking calls allowed on non-blocking threads. Every other one fails the tests.
 * <ul>
 *   <li>{@code UUID.randomUUID()} reads {@code /dev/urandom} through a buffered SecureRandom, which
 *   does not block in practice.</li>
 *   <li>Jackson builds each deserializer once, under a lock, and caches it. Under a burst of first
 *   requests the threads briefly wait for that CPU-only warm-up; it never waits for I/O.</li>
 * </ul>
 */
public class AllowedBlockingCallsIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("java.util.UUID", "randomUUID");
        builder.allowBlockingCallsInside("tools.jackson.databind.deser.DeserializerCache", "_createAndCacheValueDeserializer");
    }
}
