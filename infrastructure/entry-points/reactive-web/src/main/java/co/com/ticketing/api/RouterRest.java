package co.com.ticketing.api;

import co.com.ticketing.api.error.ErrorResponseMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** HTTP API, served only by the {@code api} role (or {@code all}). */
@Configuration
@ConditionalOnExpression("'${app.role:all}' matches 'api|all'")
public class RouterRest {

    @Bean
    public RouterFunction<ServerResponse> routerFunction(EventHandler eventHandler, OrderHandler orderHandler,
                                                         ErrorResponseMapper errorResponseMapper) {
        return RouterFunctions.route()
                .POST("/events", eventHandler::create)
                .GET("/events", eventHandler::list)
                .GET("/events/{eventId}", eventHandler::get)
                .POST("/orders", orderHandler::place)
                .GET("/orders/{orderId}", orderHandler::get)
                .onError(Throwable.class, errorResponseMapper::toResponse)
                .build();
    }
}
