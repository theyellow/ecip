package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GroupProfileServiceTest {

    @Mock private GroupProfileRepository repository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private TelegramAccountRepository accountRepository;

    private GroupProfileService service;

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);

    @BeforeEach
    void setUp() {
        service = new GroupProfileService(repository, watchedGroupRepository, accountRepository);
    }

    private GroupProfile profile(Long chatId) {
        return GroupProfile.builder().id(1L).telegramChatId(chatId).name("Test Group").build();
    }

    @Test
    void findAll_adminMode_returnsAll() {
        when(repository.findAll()).thenReturn(Flux.just(profile(100L), profile(200L)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectNextCount(2)
                .verifyComplete();

        verify(repository).findAll();
    }

    @Test
    void findAll_tenantMode_scopesToTenant() {
        when(repository.findAllByTenantId(TENANT_UUID)).thenReturn(Flux.just(profile(100L)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, TENANT_ID)))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).findAllByTenantId(TENANT_UUID);
    }

    @Test
    void create_setsTimestamps() {
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile input = profile(123L);

        StepVerifier.create(
                        service.create(input)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        saved -> {
                            assertThat(saved.getCreatedAt()).isNotNull();
                            assertThat(saved.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void findByChatId_notFound_returns404() {
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        StepVerifier.create(
                        service.findByChatId(999L)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectErrorSatisfies(
                        ex -> {
                            assertThat(ex).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) ex).getStatusCode())
                                    .isEqualTo(HttpStatus.NOT_FOUND);
                        })
                .verify();
    }

    @Test
    void update_mergesFields() {
        GroupProfile existing = profile(123L);
        when(repository.findByTelegramChatId(123L)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile patch =
                GroupProfile.builder()
                        .name("Updated")
                        .description("New desc")
                        .moderationLevel("HIGH")
                        .autoRespond(true)
                        .welcomeMessage("Welcome!")
                        .build();

        StepVerifier.create(
                        service.update(123L, patch)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        result -> {
                            assertThat(result.getName()).isEqualTo("Updated");
                            assertThat(result.getDescription()).isEqualTo("New desc");
                            assertThat(result.getModerationLevel()).isEqualTo("HIGH");
                            assertThat(result.isAutoRespond()).isTrue();
                            assertThat(result.getWelcomeMessage()).isEqualTo("Welcome!");
                            assertThat(result.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }
}
