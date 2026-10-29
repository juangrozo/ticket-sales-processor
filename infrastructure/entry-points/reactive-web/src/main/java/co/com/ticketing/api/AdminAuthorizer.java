package co.com.ticketing.api;

import co.com.ticketing.api.config.ApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards admin endpoints with a key read from the environment (never from the repository). The
 * comparison is constant-time so response timing does not leak how much of a guess was right.
 * In production this would be an authorization server role instead of a shared key.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthorizer {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final ApiProperties properties;

    public void authorize(ServerRequest request) {
        String expected = properties.adminApiKey();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin API is disabled");
        }
        String provided = request.headers().firstHeader(ADMIN_KEY_HEADER);
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid admin key");
        }
    }
}
