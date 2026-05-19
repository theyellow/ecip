package io.emcip.admin.api.security;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
public class AdminTenantContextFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);

        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(
                        ctx ->
                                ctx.getAuthentication() != null
                                        && ctx.getAuthentication().getAuthorities().stream()
                                                .anyMatch(
                                                        a -> "ROLE_ADMIN".equals(a.getAuthority())))
                .defaultIfEmpty(false)
                .flatMap(
                        isAdmin -> {
                            if (isAdmin) {
                                return chain.filter(exchange)
                                        .contextWrite(
                                                ctx -> ReactorTenantContext.withAdminMode(ctx));
                            }
                            log.debug(
                                    "Rejected request to {} — missing X-Tenant-Id and no ADMIN"
                                            + " role",
                                    exchange.getRequest().getPath());
                            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                            return exchange.getResponse().setComplete();
                        });
    }
}
