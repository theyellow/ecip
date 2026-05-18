package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountServiceTest {

    @Mock TelegramAccountRepository repository;
    @Mock AccountWatchedGroupRepository watchedGroupRepository;
    @Mock GroupProfileRepository groupProfileRepository;
    @Mock R2dbcEntityTemplate r2dbcEntityTemplate;
    @Mock WebClient tdlibClient;

    TelegramAccountService service;

    @BeforeEach
    void setUp() {
        service =
                new TelegramAccountService(
                        repository,
                        watchedGroupRepository,
                        groupProfileRepository,
                        r2dbcEntityTemplate,
                        tdlibClient);
        ReflectionTestUtils.setField(service, "telegramApiId", 12345);
        ReflectionTestUtils.setField(service, "telegramApiHash", "abc-hash");
        TenantContext.setAdminMode(true);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void findAll_adminMode_returnsAll() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        TelegramAccount a1 =
                TelegramAccount.builder()
                        .id(id1)
                        .phoneNumber("+49100000001")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        TelegramAccount a2 =
                TelegramAccount.builder()
                        .id(id2)
                        .phoneNumber("+49100000002")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(repository.findAll()).thenReturn(Flux.just(a1, a2));

        StepVerifier.create(service.findAll().collectList())
                .assertNext(list -> assertThat(list).hasSize(2))
                .verifyComplete();
    }

    @Test
    void getById_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.getById(id))
                .expectErrorSatisfies(
                        err -> {
                            assertThat(err).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) err).getStatusCode().value())
                                    .isEqualTo(404);
                        })
                .verify();
    }

    @Test
    void create_setsStatusUnconfiguredAndApiCredentials() {
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create("+49123456789", "Test Monitor", null))
                .assertNext(
                        account -> {
                            assertThat(account.getStatus())
                                    .isEqualTo(TelegramAccountStatus.UNCONFIGURED);
                            assertThat(account.getPhoneNumber()).isEqualTo("+49123456789");
                            assertThat(account.getApiId()).isEqualTo(12345);
                            assertThat(account.getApiHash()).isEqualTo("abc-hash");
                            assertThat(account.getCreatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void delete_callsDeleteById() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(id)).verifyComplete();

        verify(repository).deleteById(id);
    }

    @Test
    void findWatchedGroups_returnsGroupProfiles() {
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

        StepVerifier.create(service.findWatchedGroups(accountId).collectList())
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).getTelegramChatId()).isEqualTo(555L);
                            assertThat(list.get(0).getName()).isEqualTo("Test Group");
                        })
                .verifyComplete();
    }
}
