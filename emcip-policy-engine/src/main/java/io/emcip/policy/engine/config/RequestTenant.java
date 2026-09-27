package io.emcip.policy.engine.config;

import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import reactor.util.context.ContextView;

/**
 * The tenant asserted by the caller of this request, as put into the Reactor context by {@link
 * TenantWebFilter}. {@code null} means no tenant was asserted: a trusted caller (ADR-009 rule 5).
 */
public final class RequestTenant {

    private RequestTenant() {}

    public static UUID of(ContextView ctx) {
        String tenantId = ReactorTenantContext.getTenantId(ctx);
        return tenantId != null ? UUID.fromString(tenantId) : null;
    }
}
