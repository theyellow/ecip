package io.emcip.admin.api.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import java.net.URI;
import java.util.UUID;
import org.springframework.web.util.UriBuilder;
import reactor.util.context.ContextView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

    /** Builds a knowledge-engine URI and appends {@code tenantId} when a tenant is asserted. */
    static URI path(UriBuilder b, String path, String tenant, Object... vars) {
        b.path(path);
        if (tenant != null) {
            b.queryParam("tenantId", tenant);
        }
        return b.build(vars);
    }

    /**
     * Rewrites a JSON object body so its {@code tenantId} is the resolved tenant (KNOW-F1), never a
     * tenant-bound caller's choice.
     *
     * @throws IllegalArgumentException if the body is not a JSON object, or an ADMIN names a tenant
     *     that is not a UUID
     */
    static String rewriteBody(ObjectMapper objectMapper, String body, ContextView ctx) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Request body is not valid JSON", e);
        }
        if (!(parsed instanceof ObjectNode request)) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        JsonNode requested = request.get("tenantId");
        String tenant =
                resolve(ctx, requested == null || requested.isNull() ? null : requested.asString());
        if (tenant == null) {
            request.putNull("tenantId");
        } else {
            request.put("tenantId", tenant);
        }
        return objectMapper.writeValueAsString(request);
    }
}
