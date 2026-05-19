package io.emcip.audit.service.config;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that reads the {@code X-Tenant-Id} header and propagates it via Reactor Context
 * for the duration of the request.
 */
@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }
        return chain.filter(exchange);
    }
}
