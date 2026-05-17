package io.emcip.common.tenant;

public final class TenantContext {

    public static final String HEADER_NAME = "X-Tenant-Id";
    public static final String KAFKA_HEADER = "tenant_id";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ADMIN_MODE = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void setAdminMode(boolean admin) {
        ADMIN_MODE.set(admin);
    }

    public static boolean isAdminMode() {
        return Boolean.TRUE.equals(ADMIN_MODE.get());
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        ADMIN_MODE.remove();
    }
}
