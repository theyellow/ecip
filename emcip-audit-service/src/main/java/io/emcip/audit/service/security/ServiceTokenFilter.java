package io.emcip.audit.service.security;

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

    private final String configuredToken;

    public ServiceTokenFilter(
            @Value("${admin.service-token:internal-service-token}") String configuredToken) {
        this.configuredToken = configuredToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);
        if (token == null || !configuredToken.equals(token)) {
            log.warn("Rejected request to {} - missing or invalid service token", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
