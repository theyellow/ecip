package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RolePermissionsTest {

    @Test
    void admin_hasAllPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.ADMIN);
        assertThat(perms).containsAll(Set.of(Permission.values()));
    }

    @Test
    void tenantAdmin_hasExpectedPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms)
                .contains(
                        Permission.GROUPS_READ,
                        Permission.GROUPS_WRITE,
                        Permission.POLICY_RULES_READ,
                        Permission.POLICY_RULES_WRITE,
                        Permission.MODERATION_RULES_READ,
                        Permission.MODERATION_RULES_WRITE,
                        Permission.AUDIT_READ,
                        Permission.TELEGRAM_READ,
                        Permission.TELEGRAM_WRITE,
                        Permission.SIMULATE_WRITE);
    }

    @Test
    void tenantAdmin_lacksAdminOnlyPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms)
                .doesNotContain(
                        Permission.AI_CONFIG_READ,
                        Permission.AI_CONFIG_WRITE,
                        Permission.TENANTS_READ,
                        Permission.TENANTS_WRITE,
                        Permission.USERS_READ,
                        Permission.USERS_WRITE);
    }

    @Test
    void tenantAdmin_hasIntegrationsTenantManage() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms).contains(Permission.INTEGRATIONS_TENANT_MANAGE);
    }

    @Test
    void tenantAdmin_lacksIntegrationsGlobalManage() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms).doesNotContain(Permission.INTEGRATIONS_GLOBAL_MANAGE);
    }
}
