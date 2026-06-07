package io.emcip.moderation.service.kafka;

import io.emcip.common.events.EventSchemas.ModerationFlagEvent;
import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class PolicyDecisionConsumer {

    private static final String MODERATION_FLAGS_TOPIC = "moderation.flags";

    private final RuleEvaluationService ruleEvaluationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PolicyDecisionConsumer(
            RuleEvaluationService ruleEvaluationService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.ruleEvaluationService = ruleEvaluationService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "policies.decisions",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TenantAwareKafkaSupport.bindTenantFromRecord(record);
            String tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("No tenant context for policy decision {}, skipping", record.key());
                acknowledgment.acknowledge();
                return;
            }

            PolicyDecisionEvent event =
                    objectMapper.readValue(record.value(), PolicyDecisionEvent.class);

            String text = event.messageText();
            if (text == null || text.isBlank()) {
                log.debug(
                        "No messageText in policy decision {}, skipping moderation evaluation",
                        event.sourceEventId());
                acknowledgment.acknowledge();
                return;
            }

            Optional<EvaluationResult> result = ruleEvaluationService.evaluate(text, tenantId);

            if (result.isPresent()) {
                EvaluationResult match = result.get();
                log.info(
                        "Moderation rule '{}' matched for event {}: severity={}, action={}",
                        match.ruleName(),
                        event.sourceEventId(),
                        match.severity(),
                        match.action());

                ModerationFlagEvent flagEvent =
                        new ModerationFlagEvent(
                                UUID.randomUUID().toString(),
                                Instant.now().toString(),
                                null,
                                null,
                                event.sourceEventId(),
                                match.ruleType(),
                                match.severity(),
                                "Rule matched: " + match.ruleName(),
                                Map.of("action", match.action(), "ruleName", match.ruleName()));

                String flagJson = objectMapper.writeValueAsString(flagEvent);
                ProducerRecord<String, String> kafkaRecord =
                        new ProducerRecord<>(
                                MODERATION_FLAGS_TOPIC, event.sourceEventId(), flagJson);
                TenantAwareKafkaSupport.addTenantHeader(kafkaRecord);
                kafkaTemplate.send(kafkaRecord);
                log.debug(
                        "Published ModerationFlagEvent to {} for source event {}",
                        MODERATION_FLAGS_TOPIC,
                        event.sourceEventId());
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process policy decision: {}", record.value(), e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }
}
