package io.emcip.admin.api.service;

import io.emcip.admin.api.client.AuditServiceClient;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.ReactorTenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {

    public static final String TOPIC = "telegram.raw.messages";

    private static final Set<String> EXPECTED_EVENT_TYPES =
            Set.of("TelegramMessage", "IntentClassified", "PolicyDecision", "ModerationFlag");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AuditServiceClient auditServiceClient;

    public record SimulateTraceResult(
            String eventId, String topic, boolean partial, List<TraceStage> stages) {}

    public record TraceStage(String stage, Map<String, Object> data) {}

    public Mono<SimulateTraceResult> simulate(SimulateMessageRequest req) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    String eventId = UUID.randomUUID().toString();
                    String timestamp = Instant.now().toString();

                    EventSchemas.TelegramMessageEvent event =
                            new EventSchemas.TelegramMessageEvent(
                                    eventId,
                                    timestamp,
                                    null,
                                    null,
                                    req.getTelegramMessageId() != null
                                            ? req.getTelegramMessageId()
                                            : System.currentTimeMillis(),
                                    req.getChatId(),
                                    req.getSenderId() != null ? req.getSenderId() : "sim-user",
                                    req.getSenderType() != null ? req.getSenderType() : "USER",
                                    req.getText(),
                                    (int) (System.currentTimeMillis() / 1000),
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);

                    try {
                        String payload = objectMapper.writeValueAsString(event);
                        ProducerRecord<String, String> record =
                                new ProducerRecord<>(
                                        TOPIC, null, String.valueOf(req.getChatId()), payload);
                        if (tenantId != null) {
                            record.headers()
                                    .add("tenant_id", tenantId.getBytes(StandardCharsets.UTF_8));
                        }
                        kafkaTemplate.send(record);
                    } catch (JacksonException e) {
                        log.error("Failed to serialize simulation event", e);
                        return Mono.<SimulateTraceResult>error(
                                new RuntimeException("Failed to serialize event", e));
                    }

                    return Flux.interval(Duration.ofMillis(500))
                            .take(30)
                            .concatMap(tick -> auditServiceClient.findByCorrelationId(eventId))
                            .takeUntil(this::hasAllStages)
                            .last(emptyPageNode())
                            .map(json -> buildTraceResult(eventId, json));
                });
    }

    private boolean hasAllStages(JsonNode json) {
        JsonNode items = json.path("items");
        if (!items.isArray()) return false;
        Set<String> found = new java.util.HashSet<>();
        items.forEach(
                item -> {
                    String et = item.path("eventType").asText("");
                    if (!et.isBlank()) found.add(et);
                });
        return found.containsAll(EXPECTED_EVENT_TYPES);
    }

    private SimulateTraceResult buildTraceResult(String eventId, JsonNode json) {
        JsonNode items = json.path("items");
        List<TraceStage> stages = new ArrayList<>();
        boolean partial = !hasAllStages(json);

        if (items.isArray()) {
            items.forEach(
                    item -> {
                        String eventType = item.path("eventType").asText("");
                        JsonNode details = item.path("details");
                        TraceStage stage = mapToStage(eventType, eventId, details);
                        if (stage != null) stages.add(stage);
                    });
        }

        return new SimulateTraceResult(eventId, TOPIC, partial, stages);
    }

    private TraceStage mapToStage(String eventType, String eventId, JsonNode details) {
        return switch (eventType) {
            case "TelegramMessage" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("topic", TOPIC);
                data.put("eventId", eventId);
                yield new TraceStage("PUBLISH", data);
            }
            case "IntentClassified" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("intent", details.path("intent").asText("UNKNOWN"));
                data.put("confidence", details.path("confidence").asDouble(0.0));
                data.put("matchedRules", toList(details.path("matchedRules")));
                yield new TraceStage("CLASSIFIER", data);
            }
            case "PolicyDecision" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("policyId", details.path("policyId").asText(""));
                data.put("decision", details.path("decision").asText(""));
                data.put("actions", toList(details.path("actions")));
                data.put("reason", details.path("reason").asText(""));
                yield new TraceStage("POLICY", data);
            }
            case "ModerationFlag" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("flagType", details.path("flagType").asText(""));
                data.put("severity", details.path("severity").asText(""));
                data.put("reason", details.path("reason").asText(""));
                yield new TraceStage("MODERATION", data);
            }
            default -> null;
        };
    }

    private List<String> toList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> result.add(n.asText()));
        return result;
    }

    private JsonNode emptyPageNode() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray("items");
        node.put("total", 0);
        return node;
    }
}
