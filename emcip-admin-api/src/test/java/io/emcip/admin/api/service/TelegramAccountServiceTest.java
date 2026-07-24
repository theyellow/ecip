package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.crypto.SecretCipher;
import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountServiceTest {

    @Mock private TelegramAccountRepository repository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private GroupProfileRepository groupProfileRepository;
    @Mock private R2dbcEntityTemplate r2dbcEntityTemplate;
    @Mock private WebClient tdlibClient;
    @Mock private RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RequestBodySpec requestBodySpec;
    @Mock private RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private ResponseSpec responseSpec;

    private static final SecretCipher CIPHER =
            new SecretCipher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private TelegramAccountService service;

    @BeforeEach
    void setUp() {
        service =
                new TelegramAccountService(
                        repository,
                        watchedGroupRepository,
                        groupProfileRepository,
                        r2dbcEntityTemplate,
                        tdlibClient,
                        CircuitBreakerRegistry.ofDefaults(),
                        CIPHER);
        ReflectionTestUtils.setField(service, "telegramApiId", 12345);
        ReflectionTestUtils.setField(service, "telegramApiHash", "test-api-hash");
    }

    private TelegramAccount account(UUID id) {
        TelegramAccount a = new TelegramAccount();
        a.setId(id);
        a.setPhoneNumber("+49123456789");
        a.setStatus(TelegramAccountStatus.ACTIVE);
        return a;
    }

    @Test
    void findAll_adminMode_returnsAll() {
        UUID id = UUID.randomUUID();
        when(repository.findAll()).thenReturn(Flux.just(account(id)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void getById_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
                        service.getById(id)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("404"))
                .verify();
    }

    @Test
    void create_setsStatusUnconfiguredAndCredentials() {
        UUID tenantId = UUID.randomUUID();
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(
                        service.create("+49123", "Test Account", tenantId, null, null)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        a -> {
                            assertThat(a.getStatus()).isEqualTo(TelegramAccountStatus.UNCONFIGURED);
                            assertThat(a.getPhoneNumber()).isEqualTo("+49123");
                            assertThat(a.getTenantId()).isEqualTo(tenantId);
                            assertThat(a.getCreatedAt()).isNotNull();
                            assertThat(a.getApiId()).isEqualTo(12345);
                            assertThat(a.getApiHash()).isNotEqualTo("test-api-hash");
                            assertThat(CIPHER.decrypt(a.getApiHash(), "test"))
                                    .isEqualTo("test-api-hash");
                        })
                .verifyComplete();
    }

    @Test
    void delete_callsDeleteById() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
                        service.delete(id)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .verifyComplete();

        verify(repository).deleteById(id);
    }

    @Test
    void findWatchedGroups_returnsGroupProfiles() {
        UUID accountId = UUID.randomUUID();
        io.emcip.admin.api.entity.AccountWatchedGroup awg =
                new io.emcip.admin.api.entity.AccountWatchedGroup();
        awg.setGroupProfileId(10L);

        GroupProfile gp = new GroupProfile();
        gp.setTelegramChatId(100L);

        when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg));
        when(groupProfileRepository.findById(10L)).thenReturn(Mono.just(gp));

        StepVerifier.create(service.findWatchedGroups(accountId))
                .assertNext(p -> assertThat(p.getTelegramChatId()).isEqualTo(100L))
                .verifyComplete();
    }

    @Test
    void pushWatchedGroups_includesKnowledgeChatIdsSubset() {
        UUID accountId = UUID.randomUUID();

        GroupProfile gp1 =
                GroupProfile.builder()
                        .id(1L)
                        .telegramChatId(-1001L)
                        .name("g1")
                        .knowledgeForkEnabled(true)
                        .build();
        GroupProfile gp2 =
                GroupProfile.builder()
                        .id(2L)
                        .telegramChatId(-1002L)
                        .name("g2")
                        .knowledgeForkEnabled(false)
                        .build();

        AccountWatchedGroup awg1 = new AccountWatchedGroup();
        awg1.setGroupProfileId(1L);
        AccountWatchedGroup awg2 = new AccountWatchedGroup();
        awg2.setGroupProfileId(2L);

        TelegramAccount account =
                TelegramAccount.builder()
                        .id(accountId)
                        .phoneNumber("+49000")
                        .tenantId(UUID.randomUUID())
                        .build();
        when(repository.findById(accountId)).thenReturn(Mono.just(account));
        when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg1, awg2));
        when(groupProfileRepository.findById(1L)).thenReturn(Mono.just(gp1));
        when(groupProfileRepository.findById(2L)).thenReturn(Mono.just(gp2));

        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        when(tdlibClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString(), any(UUID.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any()))
                .thenAnswer(
                        inv -> {
                            capturedBody.set((Map<String, Object>) inv.getArgument(0));
                            return requestHeadersSpec;
                        });
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.empty());

        StepVerifier.create(service.pushWatchedGroups(accountId)).verifyComplete();

        @SuppressWarnings("unchecked")
        List<Long> knowledgeChatIds = (List<Long>) capturedBody.get().get("knowledgeChatIds");
        assertThat(knowledgeChatIds).containsExactly(-1001L);

        @SuppressWarnings("unchecked")
        List<Long> allChatIds = (List<Long>) capturedBody.get().get("chatIds");
        assertThat(allChatIds).containsExactlyInAnyOrder(-1001L, -1002L);
    }
}
