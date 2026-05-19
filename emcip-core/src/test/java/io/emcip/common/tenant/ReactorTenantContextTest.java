package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

class ReactorTenantContextTest {

    @Test
    void withTenant_populatesTenantId() {
        Context ctx = ReactorTenantContext.withTenant(Context.empty(), "tenant-abc");
        assertThat(ReactorTenantContext.getTenantId(ctx)).isEqualTo("tenant-abc");
        assertThat(ReactorTenantContext.isAdminMode(ctx)).isFalse();
    }

    @Test
    void withAdminMode_setsAdminModeTrue() {
        Context ctx = ReactorTenantContext.withAdminMode(Context.empty());
        assertThat(ReactorTenantContext.isAdminMode(ctx)).isTrue();
        assertThat(ReactorTenantContext.getTenantId(ctx)).isNull();
    }

    @Test
    void getTenantId_missingKey_returnsNull() {
        assertThat(ReactorTenantContext.getTenantId(Context.empty())).isNull();
    }

    @Test
    void isAdminMode_missingKey_returnsFalse() {
        assertThat(ReactorTenantContext.isAdminMode(Context.empty())).isFalse();
    }

    @Test
    void withTenant_overridesAdminMode() {
        Context base = ReactorTenantContext.withAdminMode(Context.empty());
        Context updated = ReactorTenantContext.withTenant(base, "tenant-xyz");
        assertThat(ReactorTenantContext.getTenantId(updated)).isEqualTo("tenant-xyz");
        assertThat(ReactorTenantContext.isAdminMode(updated)).isFalse();
    }
}
