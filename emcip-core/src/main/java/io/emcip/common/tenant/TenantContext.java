package io.emcip.common.tenant;

public final class TenantContext {

    public static final String HEADER_NAME = "X-Tenant-Id";
    public static final String KAFKA_HEADER = "tenant_id";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
