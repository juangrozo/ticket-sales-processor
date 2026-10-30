package co.com.ticketing.api.config;

import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogFactory;

/**
 * One line per HTTP request (method, path, status, duration) on the reactor.netty.http.server.AccessLog
 * logger. Health checks are skipped, and no headers are logged: they carry the admin key and the
 * idempotency key.
 */
@Configuration
public class AccessLogConfig {

    @Bean
    public NettyServerCustomizer accessLogCustomizer() {
        return server -> server.accessLog(true, AccessLogFactory.createFilter(
                args -> args.uri() == null || !args.uri().toString().startsWith("/actuator"),
                args -> AccessLog.create("{} {} {} {}ms", args.method(), args.uri(), args.status(), args.duration())));
    }
}
