package co.com.ticketing.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

class ConfigTest {

    private final WebTestClient client = WebTestClient
            .bindToRouterFunction(RouterFunctions.route(GET("/events"), _ -> ServerResponse.ok().build()))
            .webFilter(new SecurityHeadersConfig())
            .build();

    @Test
    void corsFilterShouldBeCreated() {
        assertThat(new CorsConfig().corsWebFilter(List.of("http://localhost:4200"))).isNotNull();
    }

    @Test
    void shouldAddSecurityHeaders() {
        client.get().uri("/events")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'self'; frame-ancestors 'self'; form-action 'self'")
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().doesNotExist("Server")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin");
    }
}
