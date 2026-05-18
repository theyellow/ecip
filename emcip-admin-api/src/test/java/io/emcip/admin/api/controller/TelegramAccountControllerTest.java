package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.service.TelegramAccountService;
import io.emcip.common.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock TelegramAccountService telegramAccountService;

    TelegramAccountController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setAdminMode(true);
        controller = new TelegramAccountController(telegramAccountService);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
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
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(telegramAccountService.create(anyString(), anyString(), any()))
                .thenReturn(Mono.just(account));

        TelegramAccountController.CreateAccountRequest req =
                new TelegramAccountController.CreateAccountRequest("+49123456789", "Monitor 1");

        StepVerifier.create(controller.createAccount(req))
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
}
