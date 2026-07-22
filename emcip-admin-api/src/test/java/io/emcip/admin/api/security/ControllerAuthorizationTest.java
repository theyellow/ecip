package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.admin.api.controller.AIProxyController;
import io.emcip.admin.api.controller.TelegramAccountController;
import io.emcip.admin.api.controller.TenantController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ControllerAuthorizationTest {

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
}
