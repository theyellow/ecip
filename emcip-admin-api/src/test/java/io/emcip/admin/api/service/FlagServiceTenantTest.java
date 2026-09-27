package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.client.PolicyEngineClient;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * TENANT-AUDIT F-1 (CRITICAL): a flag reply must never act on another tenant's flag — its Telegram
 * group, via its account. This check holds even if policy-engine returned the decision (defence in
 * depth: F-1 must not rest on one layer).
 */
@ExtendWith(MockitoExtension.class)
class FlagServiceTenantTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final long CHAT = -100123L;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Mock private PolicyEngineClient policyEngineClient;
    @Mock private GroupProfileRepository groupProfileRepository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private TelegramAccountRepository accountRepository;
    @Mock private WebClient tdlibClient;
    @Mock private WebClient orchestratorWebClient;
    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private AdminAuditPublisher auditPublisher;

    private FlagService flagService;

    @BeforeEach
    void setUp() {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("tdlib-adapter");
        when(circuitBreakerRegistry.circuitBreaker("tdlib-adapter")).thenReturn(cb);
        flagService =
                new FlagService(
                        policyEngineClient,
                        groupProfileRepository,
                        watchedGroupRepository,
                        accountRepository,
                        tdlibClient,
                        orchestratorWebClient,
                        circuitBreakerRegistry,
                        auditPublisher);
    }

    @Test
    void replyToAnotherTenantsFlagIs404AndNothingIsSent() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(B)));

        StepVerifier.create(
                        flagService
                                .reply("flag-1", "hi", "GROUP", false, false, null)
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectErrorSatisfies(FlagServiceTenantTest::assertNotFound)
                .verify(TIMEOUT);

        verifyNoInteractions(
                groupProfileRepository,
                watchedGroupRepository,
                accountRepository,
                tdlibClient,
                auditPublisher);
    }

    @Test
    void noteOnAnotherTenantsFlagIs404AndWritesNoAuditEvent() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(B)));

        StepVerifier.create(
                        flagService
                                .reply("flag-1", "hi", "NOTE", false, false, null)
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectErrorSatisfies(FlagServiceTenantTest::assertNotFound)
                .verify(TIMEOUT);

        verifyNoInteractions(auditPublisher);
    }

    @Test
    void replyToAGlobalFlagIs404ForATenant() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(null)));

        StepVerifier.create(
                        flagService
                                .reply("flag-1", "hi", "GROUP", false, false, null)
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectErrorSatisfies(FlagServiceTenantTest::assertNotFound)
                .verify(TIMEOUT);

        verifyNoInteractions(groupProfileRepository, tdlibClient);
    }

    @Test
    void replyToOwnFlagLooksUpTheGroupWithinTheTenant() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(A)));
        when(groupProfileRepository.findByTelegramChatIdAndTenantId(CHAT, A))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                        flagService
                                .reply("flag-1", "hi", "GROUP", false, false, null)
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectError(IllegalArgumentException.class)
                .verify(TIMEOUT);

        verify(groupProfileRepository).findByTelegramChatIdAndTenantId(CHAT, A);
        verify(groupProfileRepository, never()).findByTelegramChatId(any());
    }

    private static void assertNotFound(Throwable e) {
        assertThat(e)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static JsonNode decisionOf(UUID tenant) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("id", "flag-1");
        if (tenant != null) {
            decision.put("tenantId", tenant.toString());
        } else {
            decision.putNull("tenantId");
        }
        decision.putObject("metadata").put("chatId", CHAT).put("telegramMessageId", 7L);
        return decision;
    }
}
