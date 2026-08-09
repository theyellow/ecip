package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.service.TelegramAccountService;
import io.emcip.common.crypto.PlaintextSecretException;
import io.emcip.common.tenant.ReactorTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock TelegramAccountService telegramAccountService;

    TelegramAccountController controller;
    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new TelegramAccountController(telegramAccountService);
        webTestClient =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    @Test
    void listAccounts_sessionStringSensitiveFieldStripped() {
        UUID id = UUID.randomUUID();
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(id)
                        .phoneNumber("+49123456789")
                        .apiId(12345)
                        .apiHash("abc123")
                        .displayName("Monitor 1")
                        .sessionString("secret-session-data")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(telegramAccountService.findAll()).thenReturn(Flux.just(account));

        StepVerifier.create(controller.listAccounts())
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).get("sessionStringSet")).isEqualTo(true);
                            assertThat(list.get(0)).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }

    @Test
    void createAccount_returns201() {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber("+49123456789")
                        .apiId(12345)
                        .displayName("")
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(telegramAccountService.create(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Mono.just(account));

        TelegramAccountController.CreateAccountRequest req =
                new TelegramAccountController.CreateAccountRequest(
                        "+49123456789", "Monitor 1", null, null);

        StepVerifier.create(
                        controller
                                .createAccount(req)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        map -> {
                            assertThat(map.get("status")).isEqualTo("UNCONFIGURED");
                            assertThat(map.get("phoneNumber")).isEqualTo("+49123456789");
                            assertThat(map).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }

    @Test
    void deleteAccount_returns204() {
        UUID id = UUID.randomUUID();
        when(telegramAccountService.delete(id)).thenReturn(Mono.empty());

        StepVerifier.create(controller.deleteAccount(id)).verifyComplete();

        verify(telegramAccountService).delete(id);
    }

    @Test
    void getStatus_returns200() {
        UUID id = UUID.randomUUID();
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(id)
                        .phoneNumber("+49123456789")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(telegramAccountService.getStatus(id)).thenReturn(Mono.just(account));

        StepVerifier.create(controller.getStatus(id))
                .assertNext(
                        map -> {
                            assertThat(map.get("id")).isEqualTo(id.toString());
                            assertThat(map.get("status")).isEqualTo("ACTIVE");
                        })
                .verifyComplete();
    }

    @Test
    void logout_returns202() {
        UUID id = UUID.randomUUID();
        when(telegramAccountService.logout(id)).thenReturn(Mono.empty());

        StepVerifier.create(controller.logout(id)).verifyComplete();

        verify(telegramAccountService).logout(id);
    }

    @Test
    void getWatched_returns200() {
        UUID accountId = UUID.randomUUID();
        GroupProfile profile =
                GroupProfile.builder()
                        .id(1L)
                        .telegramChatId(555L)
                        .name("Test Group")
                        .moderationLevel("MEDIUM")
                        .build();

        when(telegramAccountService.findWatchedGroups(accountId)).thenReturn(Flux.just(profile));

        StepVerifier.create(controller.listWatched(accountId))
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).get("chatId")).isEqualTo(555L);
                            assertThat(list.get(0).get("name")).isEqualTo("Test Group");
                        })
                .verifyComplete();
    }

    @Test
    void watchGroup_returns201() {
        UUID accountId = UUID.randomUUID();
        GroupProfile profile =
                GroupProfile.builder()
                        .id(2L)
                        .telegramChatId(999L)
                        .name("New Group")
                        .moderationLevel("HIGH")
                        .build();

        when(telegramAccountService.watchGroup(eq(accountId), anyLong(), anyString()))
                .thenReturn(Mono.just(profile));

        TelegramAccountController.WatchRequest req =
                new TelegramAccountController.WatchRequest(999L, "New Group");

        StepVerifier.create(controller.watchGroup(accountId, req))
                .assertNext(
                        map -> {
                            assertThat(map.get("chatId")).isEqualTo(999L);
                            assertThat(map.get("name")).isEqualTo("New Group");
                        })
                .verifyComplete();
    }

    @Test
    void unwatchGroup_returns204() {
        UUID accountId = UUID.randomUUID();
        long chatId = 555L;
        when(telegramAccountService.unwatchGroup(accountId, chatId)).thenReturn(Mono.empty());

        StepVerifier.create(controller.unwatchGroup(accountId, chatId)).verifyComplete();

        verify(telegramAccountService).unwatchGroup(accountId, chatId);
    }

    @Test
    void discoverChats_returnsEmptyOnError() {
        UUID id = UUID.randomUUID();
        when(telegramAccountService.discoverChats(id)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(controller.discoverChats(id))
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void createAccount_invalidPhoneNumber_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phoneNumber", "not-a-phone", "displayName", "Test"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void createAccount_blankPhoneNumber_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phoneNumber", "", "displayName", "Test"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void submitCode_invalidCode_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/code")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", ""))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void reconnect_plaintextSecret_doesNotLeakSchemaOrPathsButStaysActionable() {
        UUID id = UUID.randomUUID();
        // The real exception SecretCipher throws fail-closed. Its message deliberately names the
        // table, the column and a repo file path — none of which may reach an HTTP client.
        when(telegramAccountService.reconnect(id))
                .thenReturn(Mono.error(new PlaintextSecretException("telegram_accounts.api_hash")));

        String body =
                new String(
                        webTestClient
                                .post()
                                .uri("/api/telegram/accounts/{id}/reconnect", id)
                                .exchange()
                                .expectStatus()
                                .isEqualTo(409)
                                .expectBody()
                                .jsonPath("$.code")
                                .isEqualTo("SECRET_NOT_ENCRYPTED")
                                .returnResult()
                                .getResponseBody());

        assertThat(body)
                .as("response must not disclose database schema or internal file paths")
                .doesNotContain("telegram_accounts")
                .doesNotContain("api_hash")
                .doesNotContain("docs/operations")
                .doesNotContain(".md");
        assertThat(body)
                .as("but must still tell the operator what to do")
                .contains("Re-enter the Telegram API hash");
    }

    @Test
    void reconnect_unexpectedFailure_reportsNothingAboutItsCause() {
        UUID id = UUID.randomUUID();
        // A downstream failure whose message carries an internal hostname.
        when(telegramAccountService.reconnect(id))
                .thenReturn(
                        Mono.error(
                                new RuntimeException(
                                        "Connection refused: emcip-tdlib-adapter.emcip.svc:9080")));

        String body =
                new String(
                        webTestClient
                                .post()
                                .uri("/api/telegram/accounts/{id}/reconnect", id)
                                .exchange()
                                .expectStatus()
                                .isAccepted()
                                .expectBody()
                                .jsonPath("$.code")
                                .isEqualTo("RECONNECT_FAILED")
                                .returnResult()
                                .getResponseBody());

        assertThat(body).doesNotContain("emcip-tdlib-adapter").doesNotContain("9080");
    }

    @Test
    void reconnect_decryptFailure_isNotMisreportedAsReenterTheValue() {
        UUID id = UUID.randomUUID();
        // Same exception type as the plaintext case, different meaning: the value IS encrypted but
        // cannot be read with the current key. Telling an operator to "re-enter the API hash" here
        // would talk them into overwriting recoverable data.
        when(telegramAccountService.reconnect(id))
                .thenReturn(
                        Mono.error(
                                new IllegalStateException(
                                        "Failed to decrypt secret in telegram_accounts.api_hash")));

        String body =
                new String(
                        webTestClient
                                .post()
                                .uri("/api/telegram/accounts/{id}/reconnect", id)
                                .exchange()
                                .expectStatus()
                                .isAccepted()
                                .expectBody()
                                .jsonPath("$.code")
                                .isEqualTo("RECONNECT_FAILED")
                                .returnResult()
                                .getResponseBody());

        assertThat(body).doesNotContain("Re-enter").doesNotContain("telegram_accounts");
    }
}
