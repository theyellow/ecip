package io.emcip.admin.api.service;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private PolicyEngineClient policyEngineClient;
    @Mock private GroupProfileRepository groupProfileRepository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private TelegramAccountRepository accountRepository;
    @Mock private WebClient tdlibClient;
    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

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
                        circuitBreakerRegistry,
                        kafkaTemplate,
                        new ObjectMapper());
    }

    private JsonNode pageNode() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items").addObject().put("id", "flag-1");
        page.put("total", 1);
        page.put("page", 0);
        page.put("size", 25);
        return page;
    }

    @Test
    void listFlags_withoutDecision_delegatesToClient() {
        when(policyEngineClient.listDecisions(0, 25, null)).thenReturn(Mono.just(pageNode()));

        StepVerifier.create(flagService.listFlags(0, 25, null)).expectNextCount(1).verifyComplete();
    }

    @Test
    void listFlags_withDecision_passesDecisionThrough() {
        when(policyEngineClient.listDecisions(0, 10, "SPAM")).thenReturn(Mono.just(pageNode()));

        StepVerifier.create(flagService.listFlags(0, 10, "SPAM"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void updateStatus_validStatus_delegatesToClient() {
        when(policyEngineClient.updateDecisionStatus("flag-1", "REVIEWED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(flagService.updateStatus("flag-1", "REVIEWED")).verifyComplete();
    }

    @Test
    void reply_noMetadata_returnsError() {
        ObjectNode flag = JsonNodeFactory.instance.objectNode();
        flag.putNull("metadata");
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(flag));

        StepVerifier.create(flagService.reply("flag-1", "Hello", "GROUP", true, false, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void reply_missingMetadata_returnsError() {
        ObjectNode flag = JsonNodeFactory.instance.objectNode();
        flag.put("id", "flag-1");
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(flag));

        StepVerifier.create(flagService.reply("flag-1", "Hello", "GROUP", true, false, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
