package io.emcip.knowledge.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAccessTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void readAllowsOwnAndGlobalButNotAnotherTenants() {
        assertThat(TenantAccess.canRead(A, A)).isTrue();
        assertThat(TenantAccess.canRead(null, A)).isTrue();
        assertThat(TenantAccess.canRead(B, A)).isFalse();
    }

    @Test
    void writeAllowsOwnOnly() {
        assertThat(TenantAccess.canWrite(A, A)).isTrue();
        assertThat(TenantAccess.canWrite(null, A)).isFalse();
        assertThat(TenantAccess.canWrite(B, A)).isFalse();
    }

    @Test
    void noAssertedTenantIsATrustedCaller() {
        assertThat(TenantAccess.canRead(B, null)).isTrue();
        assertThat(TenantAccess.canWrite(B, null)).isTrue();
        assertThat(TenantAccess.canWrite(null, null)).isTrue();
    }

    @Test
    void searchVisibilityFollowsTheP38aRule() {
        assertThat(TenantAccess.visibleInSearch(A, A)).isTrue();
        assertThat(TenantAccess.visibleInSearch(null, A)).isTrue();
        assertThat(TenantAccess.visibleInSearch(B, A)).isFalse();
        // null search tenant = global only, NOT "trusted caller"
        assertThat(TenantAccess.visibleInSearch(null, null)).isTrue();
        assertThat(TenantAccess.visibleInSearch(A, null)).isFalse();
    }
}
