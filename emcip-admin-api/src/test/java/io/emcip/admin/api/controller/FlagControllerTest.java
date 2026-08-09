package io.emcip.admin.api.controller;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.service.AccountSelectionException;
import io.emcip.admin.api.service.FlagService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlagControllerTest {

    @Mock private FlagService flagService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new FlagController(flagService))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    private JsonNode pageNode() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items").addObject().put("id", "flag-1");
        page.put("total", 1);
        page.put("page", 0);
        page.put("size", 50);
        return page;
    }

    @Test
    void getFlags_returnsPageResponse() {
        when(flagService.listFlags(0, 50, null, null, null, null, null))
                .thenReturn(Mono.just(pageNode()));
        webTestClient
                .get()
                .uri("/api/flags")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void getFlags_sizeCapAt200() {
        when(flagService.listFlags(0, 200, null, null, null, null, null))
                .thenReturn(Mono.just(pageNode()));
        webTestClient.get().uri("/api/flags?size=999").exchange().expectStatus().isOk();
    }

    @Test
    void getFlags_passesIntentFilter() {
        when(flagService.listFlags(0, 50, null, "SPAM", null, null, null))
                .thenReturn(Mono.just(pageNode()));
        webTestClient
                .get()
                .uri("/api/flags?intent=SPAM")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void getFlags_passesDecisionFilter() {
        when(flagService.listFlags(0, 50, "BLOCK", null, null, null, null))
                .thenReturn(Mono.just(pageNode()));
        webTestClient
                .get()
                .uri("/api/flags?decision=BLOCK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void updateStatus_returns204() {
        when(flagService.updateStatus("flag-1", "REVIEWED")).thenReturn(Mono.empty());
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void reply_returns201() {
        when(flagService.reply("flag-1", "Hello", "GROUP", true, false, null))
                .thenReturn(Mono.just(new FlagController.ReplyResponse(12345L, "GROUP", false)));

        webTestClient
                .post()
                .uri("/api/flags/flag-1/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "text",
                                "Hello",
                                "target",
                                "GROUP",
                                "replyToOriginal",
                                true,
                                "prefixModerator",
                                false))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.messageId")
                .isEqualTo(12345);
    }

    @Test
    void reply_noteTarget_returns201WithMessageIdZero() {
        when(flagService.reply("flag-1", "Internal note", "NOTE", false, false, null))
                .thenReturn(Mono.just(new FlagController.ReplyResponse(0L, "NOTE", false)));

        webTestClient
                .post()
                .uri("/api/flags/flag-1/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "text",
                                "Internal note",
                                "target",
                                "NOTE",
                                "replyToOriginal",
                                false,
                                "prefixModerator",
                                false))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.messageId")
                .isEqualTo(0)
                .jsonPath("$.target")
                .isEqualTo("NOTE");
    }

    @Test
    void chat_returnsOrchestratorResponse() {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("success", true);
        response.put("content", "Here is my analysis");
        response.put("model", "qwen3-30b-a3b");

        when(flagService.chat(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(JsonNode.class)))
                .thenReturn(Mono.just(response));

        webTestClient
                .post()
                .uri("/api/flags/test-id/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"messages\":[{\"role\":\"user\",\"content\":\"Analyse this\"}]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true)
                .jsonPath("$.content")
                .isEqualTo("Here is my analysis");
    }

    @Test
    void reply_multipleAccounts_returns409() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<FlagController.AccountOption> accounts =
                List.of(
                        new FlagController.AccountOption(id1, "Account 1", "+1111"),
                        new FlagController.AccountOption(id2, "Account 2", "+2222"));

        when(flagService.reply("flag-2", "Hello", "GROUP", false, false, null))
                .thenReturn(Mono.error(new AccountSelectionException(accounts)));

        webTestClient
                .post()
                .uri("/api/flags/flag-2/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "text",
                                "Hello",
                                "target",
                                "GROUP",
                                "replyToOriginal",
                                false,
                                "prefixModerator",
                                false))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.accounts.length()")
                .isEqualTo(2);
    }
}
