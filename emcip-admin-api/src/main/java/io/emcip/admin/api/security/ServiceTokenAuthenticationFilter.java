package io.emcip.admin.api.security;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Authenticates inbound requests from internal services via the X-Service-Token header. A valid
 * service token grants the ROLE_SERVICE authority, allowing access to internal-only paths without a
 * user JWT. The token must match the configured admin.service-token value exactly.
 */
@Component
@Slf4j
public class ServiceTokenAuthenticationFilter implements WebFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Value("${admin.service-token:internal-service-token}")
    private String configuredServiceToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String serviceToken = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);

        if (serviceToken == null) {
            return chain.filter(exchange);
        }

        if (!configuredServiceToken.equals(serviceToken)) {
            log.warn("Invalid X-Service-Token from {}", exchange.getRequest().getRemoteAddress());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        "service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }
}
