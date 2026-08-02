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
                    Permission.SIMULATE_WRITE,
                    Permission.COSTS_READ,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.RESOLUTION_REVIEW_WRITE,
                    Permission.KNOWLEDGE_READ,
                    Permission.KNOWLEDGE_WRITE,
                    Permission.INTEGRATIONS_TENANT_MANAGE);

    private static final Set<Permission> MODERATOR_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.GROUPS_WRITE,
                    Permission.POLICY_RULES_READ,
                    Permission.POLICY_RULES_WRITE,
                    Permission.MODERATION_RULES_READ,
                    Permission.MODERATION_RULES_WRITE,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.SIMULATE_WRITE,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.RESOLUTION_REVIEW_WRITE,
                    Permission.KNOWLEDGE_READ);

    private static final Set<Permission> ANALYST_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.POLICY_RULES_READ,
                    Permission.MODERATION_RULES_READ,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.COSTS_READ,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.KNOWLEDGE_READ);

    private static final Set<Permission> VIEWER_PERMISSIONS =
            EnumSet.of(Permission.GROUPS_READ, Permission.AUDIT_READ, Permission.TELEGRAM_READ);

    /**
     * ROLE_SERVICE is a service identity granted by ServiceTokenAuthenticationFilter for internal
     * service-to-service calls, path-scoped to /api/internal/**. It holds NO user Permissions, so
     * it can never satisfy a user-permission @PreAuthorize on a user-facing endpoint. Modeled here
     * so the RBAC matrix is complete; it is deliberately NOT a member of the user-assignable Role
     * enum.
     */
    public static final Set<Permission> SERVICE_PERMISSIONS = EnumSet.noneOf(Permission.class);

    private RolePermissions() {}

    public static Set<Permission> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case TENANT_ADMIN -> TENANT_ADMIN_PERMISSIONS;
            case MODERATOR -> MODERATOR_PERMISSIONS;
            case ANALYST -> ANALYST_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
        };
    }
}
