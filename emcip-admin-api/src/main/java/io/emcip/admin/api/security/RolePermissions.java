package io.emcip.admin.api.security;

import java.util.EnumSet;
import java.util.Set;

public final class RolePermissions {

    private static final Set<Permission> ADMIN_PERMISSIONS = EnumSet.allOf(Permission.class);

    private static final Set<Permission> TENANT_ADMIN_PERMISSIONS =
            EnumSet.of(
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

    private RolePermissions() {}

    public static Set<Permission> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case TENANT_ADMIN -> TENANT_ADMIN_PERMISSIONS;
        };
    }
}
