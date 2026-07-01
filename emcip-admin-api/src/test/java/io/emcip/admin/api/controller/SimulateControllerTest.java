package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService;
import io.emcip.admin.api.service.SimulationService.SimulateTraceResult;
import io.emcip.admin.api.service.SimulationService.TraceStage;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulateControllerTest {

    @Mock private SimulationService simulationService;
    @Mock private RateLimiterRegistry rateLimiterRegistry;

    private SimulateController controller;
    private WebTestClient webTestClient;

    private static final SimulateTraceResult FULL_TRACE =
            new SimulateTraceResult(
                    "test-event-id",
                    SimulationService.TOPIC,
                    false,
                    List.of(
                            new TraceStage(
                                    "PUBLISH",
                                    Map.of(
                                            "topic",
                                            SimulationService.TOPIC,
                                            "eventId",
                                            "test-event-id")),
                            new TraceStage(
                                    "CLASSIFIER",
                                    Map.of(
                                            "intent",
                                            "SPAM",
                                            "confidence",
                                            0.95,
                                            "matchedRules",
                                            List.of("SPAM"))),
                            new TraceStage(
                                    "POLICY",
                                    Map.of(
                                            "policyId",
                                            "spam-policy",
                                            "decision",
                                            "BLOCK",
                                            "actions",
                                            List.of("BLOCK"),
                                            "reason",
                                            "keyword match")),
                            new TraceStage(
                                    "MODERATION",
                                    Map.of(
                                            "flagType",
                                            "SPAM",
                                            "severity",
                                            "HIGH",
                                            "reason",
                                            "blocked by policy"))));

    @BeforeEach
    void setUp() {
        RateLimiter rateLimiter = RateLimiter.of("test", RateLimiterConfig.ofDefaults());
        when(rateLimiterRegistry.rateLimiter(anyString())).thenReturn(rateLimiter);

        controller = new SimulateController(simulationService, rateLimiterRegistry);
        webTestClient =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
        when(simulationService.simulate(any())).thenReturn(Mono.just(FULL_TRACE));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    @Test
    void simulateMessage_returnsTraceResult() {
        StepVerifier.create(controller.simulateMessage(request(12345L)))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isEqualTo("test-event-id");
                            assertThat(result.partial()).isFalse();
                            assertThat(result.stages()).hasSize(4);
                        })
                .verifyComplete();
    }

    @Test
    void simulateMessage_delegatesToService() {
        controller.simulateMessage(request(99L)).block();
        verify(simulationService).simulate(any());
    }

    @Test
    void simulateMessage_returns202() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(555L))
                .exchange()
                .expectStatus()
                .isAccepted();
    }

    @Test
    void simulateMessage_responseBodyContainsStages() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(666L))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.eventId")
                .isEqualTo("test-event-id")
                .jsonPath("$.partial")
                .isEqualTo(false)
                .jsonPath("$.stages[0].stage")
                .isEqualTo("PUBLISH")
                .jsonPath("$.stages[1].stage")
                .isEqualTo("CLASSIFIER")
                .jsonPath("$.stages[2].stage")
                .isEqualTo("POLICY")
                .jsonPath("$.stages[3].stage")
                .isEqualTo("MODERATION");
    }

    @Test
    void simulate_nullChatId_returns400() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("text", "hello"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
