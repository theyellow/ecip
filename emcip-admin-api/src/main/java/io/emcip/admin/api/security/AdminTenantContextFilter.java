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

    private record AuthInfo(boolean isAdmin, String tenantId) {}

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")
                || path.equals("/api/auth/token")
                || path.equals("/auth/token")
                || path.equals("/api/auth/refresh")) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(
                        secCtx -> {
                            var auth = secCtx.getAuthentication();
                            if (auth == null) {
                                return new AuthInfo(false, null);
                            }
                            boolean isAdmin =
                                    auth.getAuthorities().stream()
                                            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            String tenantId = isAdmin ? null : (String) auth.getDetails();
                            return new AuthInfo(isAdmin, tenantId);
                        })
                .defaultIfEmpty(new AuthInfo(false, null))
                .flatMap(
                        info -> {
                            if (info.isAdmin()) {
                                String headerTenantId =
                                        exchange.getRequest()
                                                .getHeaders()
                                                .getFirst(TenantContext.HEADER_NAME);
                                if (headerTenantId != null && !headerTenantId.isBlank()) {
                                    return chain.filter(exchange)
                                            .contextWrite(
                                                    ctx ->
                                                            ReactorTenantContext.withTenant(
                                                                    ctx, headerTenantId));
                                }
                                return chain.filter(exchange)
                                        .contextWrite(ReactorTenantContext::withAdminMode);
                            }

                            // TENANT_ADMIN: read tenantId from JWT (stored in auth.details)
                            String tenantId = info.tenantId();
                            if (tenantId != null && !tenantId.isBlank()) {
                                return chain.filter(exchange)
                                        .contextWrite(
                                                ctx ->
                                                        ReactorTenantContext.withTenant(
                                                                ctx, tenantId));
                            }

                            log.debug(
                                    "Rejected {} — authenticated user has no tenant context",
                                    exchange.getRequest().getPath());
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        });
    }
}
