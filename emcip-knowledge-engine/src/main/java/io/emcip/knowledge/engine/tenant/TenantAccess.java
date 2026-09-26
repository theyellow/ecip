package io.emcip.knowledge.engine.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Ownership rules for tenant-owned knowledge items (ADR-009). {@code asserted} is the tenant the
 * caller asserted; {@code null} means a trusted caller (platform ADMIN in admin mode, or an
 * in-cluster service), which is unrestricted. {@code itemTenant == null} marks a global item.
 */
public final class TenantAccess {

    private TenantAccess() {}

    /** Own items and global items; never another tenant's. */
    public static boolean canRead(UUID itemTenant, UUID asserted) {
        return asserted == null || itemTenant == null || itemTenant.equals(asserted);
    }

    /** Own items only: a tenant must not change shared (global) knowledge. */
    public static boolean canWrite(UUID itemTenant, UUID asserted) {
        return asserted == null || Objects.equals(itemTenant, asserted);
    }

    /**
     * Knowledge search (P3.8a): a tenant sees own + global; a search without a tenant sees global
     * only. Unlike {@link #canRead}, a null search tenant is not a trusted caller.
     */
    public static boolean visibleInSearch(UUID itemTenant, UUID searchTenant) {
        return itemTenant == null || itemTenant.equals(searchTenant);
    }
}
