package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock TelegramAccountRepository repository;
    @Mock R2dbcEntityTemplate r2dbcEntityTemplate;
    @Mock WebClient tdlibClient;
    @Mock AccountWatchedGroupRepository watchedGroupRepository;
    @Mock GroupProfileRepository groupProfileRepository;

    TelegramAccountController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        TenantContext.setAdminMode(true);
        controller =
                new TelegramAccountController(
                        repository,
                        r2dbcEntityTemplate,
                        tdlibClient,
                        watchedGroupRepository,
                        groupProfileRepository,
                        12345,
                        "abc123");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void listAccounts_returnsMaskedSessionString() {
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

        when(repository.findAll()).thenReturn(Flux.just(account));

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
    void createAccount_savesWithUnconfiguredStatus() {
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(
                        inv -> {
                            TelegramAccount a = inv.getArgument(0);
                            return Mono.just(a);
                        });

        TelegramAccountController.CreateAccountRequest req =
                new TelegramAccountController.CreateAccountRequest("+49123456789", "Monitor 1");

        StepVerifier.create(controller.createAccount(req))
                .assertNext(
                        map -> {
                            assertThat(map.get("status")).isEqualTo("UNCONFIGURED");
                            assertThat(map.get("phoneNumber")).isEqualTo("+49123456789");
                            assertThat(map).doesNotContainKey("apiHash");
                            assertThat(map).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }

    @Test
    void listWatched_returnsWatchedGroupsForAccount() {
        UUID accountId = UUID.randomUUID();
        GroupProfile profile =
                GroupProfile.builder()
                        .id(1L)
                        .telegramChatId(555L)
                        .name("Test Group")
                        .moderationLevel("MEDIUM")
                        .build();
        AccountWatchedGroup awg =
                AccountWatchedGroup.builder().accountId(accountId).groupProfileId(1L).build();

        when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg));
        when(groupProfileRepository.findById(1L)).thenReturn(Mono.just(profile));

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
    void deleteAccount_returns204() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());
        StepVerifier.create(controller.deleteAccount(id)).verifyComplete();
    }

    @Test
    void getStatus_accountNotFound_returnsError() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(controller.getStatus(id))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void submitCode_delegatesToTdlib() {
        UUID id = UUID.randomUUID();
        ExchangeFunction exchangeFunction =
                req -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        TelegramAccountController c = controllerWithTdlib(exchangeFunction);
        StepVerifier.create(c.submitCode(id, new TelegramAccountController.CodeRequest("12345")))
                .verifyComplete();
    }

    @Test
    void submitPassword_delegatesToTdlib() {
        UUID id = UUID.randomUUID();
        ExchangeFunction exchangeFunction =
                req -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        TelegramAccountController c = controllerWithTdlib(exchangeFunction);
        StepVerifier.create(
                        c.submitPassword(
                                id, new TelegramAccountController.PasswordRequest("secret")))
                .verifyComplete();
    }

    @Test
    void logout_updatesStatusToDisconnected() {
        UUID id = UUID.randomUUID();
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(id)
                        .phoneNumber("+49123")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        ExchangeFunction exchangeFunction =
                req -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        when(repository.findById(id)).thenReturn(Mono.just(account));
        when(repository.save(any())).thenReturn(Mono.just(account));

        TelegramAccountController c = controllerWithTdlib(exchangeFunction);
        StepVerifier.create(c.logout(id)).verifyComplete();
    }

    @Test
    void discoverChats_returnsEmptyListOnError() {
        UUID id = UUID.randomUUID();
        ExchangeFunction exchangeFunction = req -> Mono.error(new RuntimeException("tdlib down"));
        TelegramAccountController c = controllerWithTdlib(exchangeFunction);
        StepVerifier.create(c.discoverChats(id))
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    private TelegramAccountController controllerWithTdlib(ExchangeFunction exchangeFunction) {
        WebClient tdlib = WebClient.builder().exchangeFunction(exchangeFunction).build();
        return new TelegramAccountController(
                repository,
                r2dbcEntityTemplate,
                tdlib,
                watchedGroupRepository,
                groupProfileRepository,
                12345,
                "abc123");
    }
}
