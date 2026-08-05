package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.events.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 9 IT: consumes {@code audit.events} via the {@code emcip-audit-service-admin} group
 * (auto.offset.reset=latest) and verifies admin-audit-event semantics are preserved (not flattened
 * like the generic domain-topic consumer).
 *
 * <p>Because the admin group starts at the tail, a record produced before the consumer's partitions
 * are assigned would be silently missed — that's a genuine race with {@code latest}, not a flake to
 * paper over. We wait for partition assignment before producing anything, and for the negative
 * (no-tenant-header) test we use a same-key sentinel record as a read-your-writes barrier instead
 * of a fixed sleep, so the assertion cannot false-pass.
 */
class AdminAuditEventPersistenceIT extends AbstractAuditIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private DatabaseClient databaseClient;
    @Autowired private KafkaListenerEndpointRegistry registry;

    @BeforeEach
    void cleanUp() {
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();

        // The admin group uses auto.offset.reset=latest: a record produced before this
        // consumer's partitions are assigned is silently missed (not redelivered later).
        // Wait for assignment before any test produces to audit.events.
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                registry.getListenerContainers().stream()
                                        .anyMatch(
                                                c ->
                                                        "emcip-audit-service-admin"
                                                                        .equals(c.getGroupId())
                                                                && c.getAssignedPartitions() != null
                                                                && !c.getAssignedPartitions()
                                                                        .isEmpty()));
    }

    private String json(String eventId, String action, String actor, String outcome)
            throws Exception {
        var event =
                new EventSchemas.AuditEvent(
                        eventId,
                        Instant.now().toString(),
                        null,
                        null,
                        null,
                        action,
                        actor,
                        "Session",
                        actor,
                        Map.of("reason", "BAD_PASSWORD"),
                        outcome);
        return new ObjectMapper().writeValueAsString(event);
    }

    @Test
    void adminAuditEvent_withTenantHeader_persistsPreservingActionAndOutcome() throws Exception {
        String eventId = "admin-audit-001";
        var pr =
                new ProducerRecord<>(
                        "audit.events", "bob", json(eventId, "LOGIN_FAILURE", "bob", "FAILURE"));
        String tenant = "00000000-0000-0000-0000-000000000001";
        pr.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(pr).get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            AuditEventEntity saved =
                                    auditEventRepository.findByEventId(eventId).block();
                            assertThat(saved).isNotNull();
                            assertThat(saved.getAction()).isEqualTo("LOGIN_FAILURE");
                            assertThat(saved.getOutcome()).isEqualTo("FAILURE");
                            assertThat(saved.getActorId()).isEqualTo("bob");
                            assertThat(saved.getActorType()).isEqualTo("ADMIN");
                            assertThat(saved.getSourceService()).isEqualTo("emcip-admin-api");
                            assertThat(saved.getTenantId()).isEqualTo(UUID.fromString(tenant));
                            assertThat(saved.getIntegrityHash()).isNotBlank();
                        });
    }

    @Test
    void adminAuditEvent_withoutTenantHeader_isRejected_notPersisted() throws Exception {
        String eventId = "admin-audit-002";
        String sentinelEventId = "admin-audit-002-sentinel";
        String key = "same-partition-key";

        // (a) no tenant header — must be rejected fail-closed.
        var noHeaderRecord =
                new ProducerRecord<>(
                        "audit.events", key, json(eventId, "LOGIN_FAILURE", "bob", "FAILURE"));

        // (b) sentinel WITH a valid tenant header, same key -> same partition, delivered after
        // (a) since Kafka preserves per-partition order. Its persistence proves (a) was already
        // processed (and rejected), without a fixed sleep.
        var sentinelRecord =
                new ProducerRecord<>(
                        "audit.events",
                        key,
                        json(sentinelEventId, "LOGIN_FAILURE", "bob", "FAILURE"));
        String tenant = "00000000-0000-0000-0000-000000000001";
        sentinelRecord
                .headers()
                .add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(noHeaderRecord).get();
        kafkaTemplate.send(sentinelRecord).get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                auditEventRepository
                                                        .findByEventId(sentinelEventId)
                                                        .block())
                                        .isNotNull());

        assertThat(auditEventRepository.findByEventId(eventId).block()).isNull();
    }
}
