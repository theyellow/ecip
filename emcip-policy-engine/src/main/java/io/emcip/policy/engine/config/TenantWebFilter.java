package io.emcip.policy.engine.config;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the {@code X-Tenant-Id} header into the Reactor context for the request (ADR-009 rule 8).
 * Controllers read it with {@link RequestTenant#of} and pass it explicitly into their queries: the
 * blocking JPA calls run on {@code boundedElastic}, so the ThreadLocal {@link TenantContext} and
 * the Hibernate {@code tenantFilter} (Kafka path) never see an HTTP request's tenant.
 */
@Slf4j
@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }
        try {
            UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected request with a malformed {} header", TenantContext.HEADER_NAME);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange)
                .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
    }
}
