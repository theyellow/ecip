package io.emcip.admin.api.integration;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class EnrichmentTriggerPublisher {

    private static final String TOPIC = "knowledge.enrichment.trigger";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(UUID sourceId, UUID runId) {
        try {
            String payload =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "sourceId", sourceId.toString(),
                                    "runId", runId.toString()));
            kafkaTemplate.send(TOPIC, sourceId.toString(), payload);
            log.debug("Published enrichment trigger: sourceId={} runId={}", sourceId, runId);
        } catch (Exception e) {
            log.error("Failed to publish enrichment trigger: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish enrichment trigger", e);
        }
    }
}
