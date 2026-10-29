package co.com.ticketing.usecase.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * {@code UUID.randomUUID()} reads {@code /dev/urandom} through a buffered SecureRandom. That read
 * never blocks once the kernel pool is seeded, so it is allowed; every other blocking call still
 * fails the tests. Identifiers stay cryptographically random because order ids are exposed in URLs.
 */
public class SecureRandomBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("java.util.UUID", "randomUUID");
    }
}
