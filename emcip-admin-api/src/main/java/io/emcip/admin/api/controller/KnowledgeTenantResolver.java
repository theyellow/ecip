package io.emcip.admin.api.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import reactor.util.context.ContextView;

/**
 * Decides which tenant a knowledge request runs as (P3.8a). The caller's identity decides, never a
 * tenant-bound caller's request: {@code AdminTenantContextFilter} has already bound the JWT tenant
 * (or an ADMIN's {@code X-Tenant-ID} header) into the Reactor context.
 */
final class KnowledgeTenantResolver {

    private KnowledgeTenantResolver() {}

    /**
     * @param requested the tenant the request names (body or query), may be null
     * @return the tenant to forward, or null for global-only knowledge
     * @throws IllegalArgumentException if an ADMIN in admin mode names something that is not a UUID
     */
    static String resolve(ContextView ctx, String requested) {
        String bound = ReactorTenantContext.getTenantId(ctx);
        if (bound != null) {
            return bound;
        }
        if (ReactorTenantContext.isAdminMode(ctx) && requested != null && !requested.isBlank()) {
            return UUID.fromString(requested).toString();
        }
        return null;
    }
}
