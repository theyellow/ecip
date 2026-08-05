package io.emcip.admin.api.audit;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuditPublisher {

    private static final String TOPIC = "audit.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Publishes an admin audit event to the audit.events Kafka topic with outcome {@code SUCCESS}.
     *
     * @param action action verb (e.g. LOGIN_SUCCESS, USER_CREATED, ROLE_CHANGED)
     * @param resourceType entity type (e.g. User, Tenant, PolicyRule)
     * @param resourceId ID of the affected resource
     * @param actor username of the admin performing the action
     * @param tenantId tenant context (nullable for ADMIN users)
     * @param details additional key-value details (nullable)
     */
    public void publish(
            String action,
            String resourceType,
            String resourceId,
            String actor,
            UUID tenantId,
            Map<String, Object> details) {
        publish(action, resourceType, resourceId, actor, tenantId, details, "SUCCESS");
    }

    /**
     * Publishes an admin audit event to the audit.events Kafka topic.
     *
     * @param action action verb (e.g. LOGIN_SUCCESS, USER_CREATED, ROLE_CHANGED)
     * @param resourceType entity type (e.g. User, Tenant, PolicyRule)
     * @param resourceId ID of the affected resource
     * @param actor username of the admin performing the action
     * @param tenantId tenant context (nullable for ADMIN users)
     * @param details additional key-value details (nullable)
     * @param outcome outcome of the action (e.g. SUCCESS, FAILURE)
     */
    public void publish(
            String action,
            String resourceType,
            String resourceId,
            String actor,
            UUID tenantId,
            Map<String, Object> details,
            String outcome) {
        try {
            var event =
                    new EventSchemas.AuditEvent(
                            UUID.randomUUID().toString(),
                            Instant.now().toString(),
                            null, // defaults to AUDIT_EVENT_V1
                            null, // defaults to "Audit"
                            null, // no sourceEventId for admin operations
                            action,
                            actor,
                            resourceType,
                            resourceId,
                            details,
                            outcome);

            String json = objectMapper.writeValueAsString(event);
            var record = new ProducerRecord<String, String>(TOPIC, resourceId, json);
            TenantAwareKafkaSupport.addTenantHeader(record, tenantId);
            kafkaTemplate.send(record);

            log.debug(
                    "Published audit event: action={}, resource={}/{}, actor={}, outcome={}",
                    action,
                    resourceType,
                    resourceId,
                    actor,
                    outcome);
        } catch (Exception e) {
            // Audit publishing must never break the main operation
            log.error("Failed to publish audit event: action={}, error={}", action, e.getMessage());
        }
    }
}
