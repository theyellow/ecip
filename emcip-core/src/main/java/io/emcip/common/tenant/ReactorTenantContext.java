package io.emcip.common.tenant;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Reactor Context keys and helpers for propagating tenant information in reactive pipelines.
 *
 * <p>Use {@link #withTenant} / {@link #withAdminMode} in WebFilters via {@code
 * chain.filter(exchange).contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, id))}, and {@link
 * #getTenantId} / {@link #isAdminMode} in services via {@code Mono.deferContextual(ctx -> ...)} /
 * {@code Flux.deferContextual(ctx -> ...)}.
 *
 * <p>The blocking {@link TenantContext} ThreadLocal is unchanged and remains correct for Kafka
 * consumer handlers and servlet-based services where thread-per-request is guaranteed.
 */
public final class ReactorTenantContext {

    public static final String TENANT_ID_KEY = "emcip.tenantId";
    public static final String ADMIN_MODE_KEY = "emcip.adminMode";

    private ReactorTenantContext() {}

    /**
     * Returns a new context with tenantId set and adminMode=false. Use as: {@code
     * chain.filter(exchange).contextWrite(ctx -> withTenant(ctx, tenantId))}
     */
    public static Context withTenant(Context ctx, String tenantId) {
        return ctx.put(TENANT_ID_KEY, tenantId).put(ADMIN_MODE_KEY, false);
    }

    /**
     * Returns a new context with adminMode=true. Use as: {@code
     * chain.filter(exchange).contextWrite(ReactorTenantContext::withAdminMode)}
     */
    public static Context withAdminMode(Context ctx) {
        return ctx.put(ADMIN_MODE_KEY, true);
    }

    /** Returns the tenantId from the context, or {@code null} if not set. */
    public static String getTenantId(ContextView ctx) {
        return ctx.getOrDefault(TENANT_ID_KEY, null);
    }

    /** Returns whether admin mode is active in the context. */
    public static boolean isAdminMode(ContextView ctx) {
        return ctx.getOrDefault(ADMIN_MODE_KEY, Boolean.FALSE);
    }
}
