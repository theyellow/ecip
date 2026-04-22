package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setAndGetTenantId() {
        TenantContext.setTenantId("tenant-abc");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-abc");
    }

    @Test
    void clearRemovesTenantId() {
        TenantContext.setTenantId("tenant-abc");
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void defaultIsNull() {
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
