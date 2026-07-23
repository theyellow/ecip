package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.admin.api.controller.AIProxyController;
import io.emcip.admin.api.controller.TelegramAccountController;
import io.emcip.admin.api.controller.TenantController;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class ControllerAuthorizationTest {

    /**
     * Mapping annotations considered "write" operations for the invariant test below. A method
     * annotated with any of these must carry its own method-level {@code @PreAuthorize} containing
     * {@code _WRITE} — the class-level annotation is not enough, since that is exactly how RT2-004
     * shipped (a write method silently inheriting the class's read-level authority).
     */
    private static final List<Class<? extends Annotation>> WRITE_MAPPING_ANNOTATIONS =
            List.of(PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    private String authorityOf(Class<?> controller, String methodName) {
        Method method =
                Arrays.stream(controller.getDeclaredMethods())
                        .filter(m -> m.getName().equals(methodName))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "No method " + methodName + " on " + controller));
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        return annotation == null ? null : annotation.value();
    }

    @Test
    void telegramWriteEndpointsRequireTelegramWrite() {
        List<String> writeMethods =
                List.of(
                        "createAccount",
                        "deleteAccount",
                        "reconnect",
                        "submitCode",
                        "submitPassword",
                        "logout",
                        "syncWatchedGroups",
                        "watchGroup",
                        "unwatchGroup");
        for (String m : writeMethods) {
            assertThat(authorityOf(TelegramAccountController.class, m))
                    .as("TelegramAccountController.%s must require TELEGRAM_WRITE", m)
                    .isEqualTo("hasAuthority('TELEGRAM_WRITE')");
        }
    }

    @Test
    void telegramReadEndpointsRequireTelegramRead() {
        List<String> readMethods =
                List.of("listAccounts", "getStatus", "discoverChats", "listWatched");
        for (String m : readMethods) {
            assertThat(authorityOf(TelegramAccountController.class, m))
                    .as("TelegramAccountController.%s must require TELEGRAM_READ", m)
                    .isEqualTo("hasAuthority('TELEGRAM_READ')");
        }
    }

    @Test
    void tenantWriteEndpointsRequireTenantsWrite() {
        for (String m : List.of("createTenant", "updateTenant", "deleteTenant")) {
            assertThat(authorityOf(TenantController.class, m))
                    .as("TenantController.%s must require TENANTS_WRITE", m)
                    .isEqualTo("hasAuthority('TENANTS_WRITE')");
        }
    }

    @Test
    void aiConfigWriteEndpointsRequireAiConfigWrite() {
        List<String> writeMethods =
                List.of(
                        "createModel",
                        "updateModel",
                        "deleteModel",
                        "createTemplate",
                        "updateTemplate",
                        "deleteTemplate",
                        "createProviderConfig",
                        "updateProviderConfig",
                        "deleteProviderConfig",
                        "warmUp");
        for (String m : writeMethods) {
            assertThat(authorityOf(AIProxyController.class, m))
                    .as("AIProxyController.%s must require AI_CONFIG_WRITE", m)
                    .isEqualTo("hasAuthority('AI_CONFIG_WRITE')");
        }
    }

    /**
     * Invariant, not a whitelist: every method mapped as a write operation (POST/PUT/DELETE/PATCH)
     * on these controllers must carry its own method-level {@code @PreAuthorize} whose expression
     * contains {@code _WRITE}. Unlike the named-method tests above, this closes the regression
     * window for write methods added in the future — a new method that forgets its
     * {@code @PreAuthorize} and silently inherits the class-level read authority (exactly the
     * RT2-004 bug) fails this test even though nobody added it to a hardcoded list.
     */
    @Test
    void everyWriteMappedMethodHasMethodLevelWritePreAuthorize() {
        List<Class<?>> controllers =
                List.of(
                        TelegramAccountController.class,
                        TenantController.class,
                        AIProxyController.class);

        List<String> violations = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                boolean isWriteMapped =
                        WRITE_MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
                if (!isWriteMapped) {
                    continue;
                }
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (preAuthorize == null || !preAuthorize.value().contains("_WRITE")) {
                    violations.add(
                            controller.getSimpleName()
                                    + "."
                                    + method.getName()
                                    + " -> "
                                    + (preAuthorize == null
                                            ? "<no @PreAuthorize>"
                                            : preAuthorize.value()));
                }
            }
        }

        assertThat(violations)
                .as(
                        "Every @PostMapping/@PutMapping/@DeleteMapping/@PatchMapping method must"
                                + " carry a method-level @PreAuthorize containing _WRITE")
                .isEmpty();
    }
}
