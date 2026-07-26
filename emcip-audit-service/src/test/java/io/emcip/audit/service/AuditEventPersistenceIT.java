package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

class AuditEventPersistenceIT extends AbstractAuditIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private AuditEventRepository auditEventRepository;

    @Autowired private DatabaseClient databaseClient;

    @BeforeEach
    void cleanUp() {
        // TRUNCATE bypasses the row-level DELETE trigger (added in Task 4).
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void telegramMessageEvent_consumedFromKafka_isPersistedAsAuditRecord() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "audit-persist-001",
                        Instant.now().toString(),
                        null,
                        null,
                        300L,
                        400L,
                        "user-audit-persist-1",
                        "USER",
                        "hello from audit persistence test",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null);
        String json = new ObjectMapper().writeValueAsString(event);

        kafkaTemplate.send("telegram.raw.messages", "audit-persist-001", json).get();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            AuditEventEntity saved =
                                    auditEventRepository.findByEventId("audit-persist-001").block();
                            assertThat(saved).isNotNull();
                            assertThat(saved.getEventType()).isEqualTo("TelegramMessage");
                            assertThat(saved.getSourceService()).isEqualTo("emcip-tdlib-adapter");
                            assertThat(saved.getOutcome()).isEqualTo("PROCESSED");
                        });
    }
}
