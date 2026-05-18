package io.emcip.tdlib.adapter.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ServiceTokenFilter implements WebFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Value("${admin.service-token:internal-service-token}")
    private String configuredToken;

    @PostConstruct
    void validateToken() {
        if (configuredToken.equals("internal-service-token")) {
            throw new IllegalStateException(
                    "Service token must be overridden via ADMIN_SERVICE_TOKEN environment"
                            + " variable");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // Actuator endpoints are always permitted
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        // All API and internal endpoints require the service token
        String token = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);
        if (token == null || !configuredToken.equals(token)) {
            log.warn("Rejected request to {} — missing or invalid X-Service-Token", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
