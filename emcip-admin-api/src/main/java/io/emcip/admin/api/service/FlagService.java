package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.emcip.admin.api.controller.FlagController;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
public class FlagService {

    private final PolicyEngineClient policyEngineClient;
    private final GroupProfileRepository groupProfileRepository;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final TelegramAccountRepository accountRepository;
    private final WebClient tdlibClient;
    private final WebClient orchestratorWebClient;
    private final CircuitBreaker tdlibCircuitBreaker;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FlagService(
            PolicyEngineClient policyEngineClient,
            GroupProfileRepository groupProfileRepository,
            AccountWatchedGroupRepository watchedGroupRepository,
            TelegramAccountRepository accountRepository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            @Qualifier("orchestratorWebClient") WebClient orchestratorWebClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.policyEngineClient = policyEngineClient;
        this.groupProfileRepository = groupProfileRepository;
        this.watchedGroupRepository = watchedGroupRepository;
        this.accountRepository = accountRepository;
        this.tdlibClient = tdlibClient;
        this.orchestratorWebClient = orchestratorWebClient;
        this.tdlibCircuitBreaker = circuitBreakerRegistry.circuitBreaker("tdlib-adapter");
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonNode> listFlags(
            int page,
            int size,
            String decision,
            String intent,
            String from,
            String to,
            Double minConfidence) {
        return policyEngineClient.listDecisions(
                page, size, decision, intent, from, to, minConfidence);
    }

    public Mono<Void> updateStatus(String id, String status) {
        return policyEngineClient.updateDecisionStatus(id, status);
    }

    public Mono<FlagController.ReplyResponse> reply(
            String flagId,
            String text,
            String target,
            boolean replyToOriginal,
            boolean prefixModerator,
            UUID accountId) {

        return policyEngineClient
                .getDecision(flagId)
                .flatMap(
                        flag -> {
                            JsonNode meta = flag.get("metadata");
                            if (meta == null || meta.isNull()) {
                                return Mono.error(
                                        new IllegalArgumentException("Flag has no metadata"));
                            }
                            long chatId = meta.get("chatId").asLong();
                            String senderId =
                                    meta.has("senderId") ? meta.get("senderId").asText() : null;
                            long telegramMessageId =
                                    meta.has("telegramMessageId")
                                            ? meta.get("telegramMessageId").asLong()
                                            : 0L;

                            return groupProfileRepository
                                    .findByTelegramChatId(chatId)
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new IllegalArgumentException(
                                                            "No group profile found for chatId "
                                                                    + chatId)))
                                    .flatMap(profile -> resolveAccount(accountId, profile, chatId))
                                    .map(
                                            account ->
                                                    new AccountWithMeta(
                                                            account,
                                                            chatId,
                                                            senderId,
                                                            telegramMessageId));
                        })
                .flatMap(
                        awm ->
                                sendAndAudit(
                                        awm,
                                        flagId,
                                        text,
                                        target,
                                        replyToOriginal,
                                        prefixModerator));
    }

    public Mono<FlagController.AnalyseResponse> analyse(String flagId) {
        return policyEngineClient
                .getDecision(flagId)
                .flatMap(
                        flag -> {
                            String prompt = buildAnalysisPrompt(flag);
                            java.util.Map<String, Object> body =
                                    java.util.Map.of("prompt", prompt, "taskType", "GENERAL");
                            return orchestratorWebClient
                                    .post()
                                    .uri("/api/analyse")
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(tools.jackson.databind.JsonNode.class)
                                    .map(
                                            result ->
                                                    new FlagController.AnalyseResponse(
                                                            result.path("success").asBoolean(false),
                                                            result.path("analysis").asText(""),
                                                            result.path("model").asText(null)));
                        });
    }

    private String buildAnalysisPrompt(tools.jackson.databind.JsonNode flag) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyse this moderation flag:\n\n");
        sb.append("Intent: ").append(flag.path("originalIntent").asText("unknown")).append("\n");
        sb.append("Decision: ").append(flag.path("decision").asText("unknown")).append("\n");
        sb.append("Confidence: ")
                .append(String.format("%.1f%%", flag.path("confidence").asDouble(0) * 100))
                .append("\n");
        sb.append("Reason: ").append(flag.path("reason").asText("none")).append("\n");
        tools.jackson.databind.JsonNode meta = flag.path("metadata");
        if (!meta.isMissingNode() && !meta.isNull() && meta.has("messageText")) {
            sb.append("Message: ").append(meta.path("messageText").asText()).append("\n");
        }
        sb.append(
                "\n"
                    + "Is the decision appropriate? Explain briefly and suggest any better action"
                    + " if relevant.");
        return sb.toString();
    }

    private Mono<TelegramAccount> resolveAccount(
            UUID accountId, GroupProfile profile, long chatId) {
        if (accountId != null) {
            return watchedGroupRepository
                    .existsByAccountIdAndGroupProfileId(accountId, profile.getId())
                    .flatMap(
                            exists -> {
                                if (!exists) {
                                    return Mono.error(
                                            new IllegalArgumentException(
                                                    "Account "
                                                            + accountId
                                                            + " does not watch chat "
                                                            + chatId));
                                }
                                return accountRepository.findById(accountId);
                            })
                    .switchIfEmpty(
                            Mono.error(
                                    new IllegalArgumentException(
                                            "Account not found: " + accountId)));
        }

        return watchedGroupRepository
                .findByGroupProfileId(profile.getId())
                .flatMap(awg -> accountRepository.findById(awg.getAccountId()))
                .collectList()
                .flatMap(
                        accounts -> {
                            if (accounts.isEmpty()) {
                                return Mono.error(
                                        new IllegalArgumentException(
                                                "No accounts watch chat " + chatId));
                            }
                            if (accounts.size() == 1) {
                                return Mono.just(accounts.getFirst());
                            }
                            return Mono.error(
                                    new AccountSelectionException(
                                            accounts.stream()
                                                    .map(
                                                            a ->
                                                                    new FlagController
                                                                            .AccountOption(
                                                                            a.getId(),
                                                                            a.getDisplayName(),
                                                                            a.getPhoneNumber()))
                                                    .toList()));
                        });
    }

    private Mono<FlagController.ReplyResponse> sendAndAudit(
            AccountWithMeta awm,
            String flagId,
            String text,
            String target,
            boolean replyToOriginal,
            boolean prefixModerator) {

        String messageText = prefixModerator ? "[Moderator]: " + text : text;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatId", awm.chatId());
        body.put("text", messageText);

        if ("DM".equalsIgnoreCase(target) && awm.senderId() != null) {
            Long recipientUserId = parseSenderId(awm.senderId());
            if (recipientUserId != null) {
                body.put("recipientUserId", recipientUserId);
            }
        }

        if (replyToOriginal && awm.telegramMessageId() > 0) {
            body.put("replyToMessageId", awm.telegramMessageId());
        }

        return tdlibClient
                .post()
                .uri("/internal/send-message/{accountId}", awm.account().getId())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker))
                .flatMap(
                        response -> {
                            long messageId =
                                    response.has("messageId")
                                            ? response.get("messageId").asLong()
                                            : 0L;

                            publishAuditEvent(
                                    flagId,
                                    target,
                                    awm.chatId(),
                                    awm.account().getId(),
                                    awm.telegramMessageId(),
                                    replyToOriginal,
                                    prefixModerator);

                            return Mono.just(
                                    new FlagController.ReplyResponse(messageId, target, false));
                        });
    }

    private void publishAuditEvent(
            String flagId,
            String target,
            long chatId,
            UUID accountId,
            long telegramMessageId,
            boolean replyToOriginal,
            boolean prefixModerator) {
        try {
            ObjectNode event = JsonNodeFactory.instance.objectNode();
            event.put("eventType", "OPERATOR_REPLY");
            event.put("action", "SEND_MESSAGE");
            event.put("sourceService", "admin-api");
            event.put("resourceId", flagId);
            event.put("outcome", "SUCCESS");

            ObjectNode details = event.putObject("details");
            details.put("target", target);
            details.put("chatId", chatId);
            details.put("accountId", accountId.toString());
            details.put("telegramMessageId", telegramMessageId);
            details.put("replyToOriginal", replyToOriginal);
            details.put("prefixModerator", prefixModerator);

            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(new ProducerRecord<>("audit.events", flagId, json));
        } catch (JacksonException e) {
            log.error("Failed to publish audit event for flag {}", flagId, e);
        }
    }

    private static Long parseSenderId(String senderId) {
        if (senderId == null) return null;
        String numeric = senderId.startsWith("user:") ? senderId.substring(5) : senderId;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record AccountWithMeta(
            TelegramAccount account, long chatId, String senderId, long telegramMessageId) {}
}
