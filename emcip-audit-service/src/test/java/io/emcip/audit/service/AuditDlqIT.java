package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

class AuditDlqIT extends AbstractAuditIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    private static final String TOPIC = "telegram.raw.messages";
    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void clean() {
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void malformedRecord_goesToDlq_andGoodRecordStillProcessed() throws Exception {
        // Malformed JSON with a valid tenant header -> non-retryable -> DLQ.
        send(TOPIC, "bad-1", "{ not json", TENANT);

        // A good record after it must still be processed (offset advanced past the bad one).
        TelegramMessageEvent good =
                new TelegramMessageEvent(
                        "good-1",
                        Instant.now().toString(),
                        null,
                        null,
                        1L,
                        2L,
                        "u",
                        "USER",
                        "hi",
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
        send(TOPIC, "good-1", new ObjectMapper().writeValueAsString(good), TENANT);

        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(
                        () -> {
                            AuditEventEntity saved = repository.findByEventId("good-1").block();
                            assertThat(saved).isNotNull();
                        });

        // The bad record was never persisted...
        assertThat(repository.count().block()).isEqualTo(1L);
        // ...and it landed on the DLQ.
        assertThat(dlqHasRecord(TOPIC + ".dlq")).isTrue();
    }

    private void send(String topic, String key, String value, String tenant) throws Exception {
        ProducerRecord<String, String> pr = new ProducerRecord<>(topic, key, value);
        pr.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(pr).get();
    }

    private boolean dlqHasRecord(String dlqTopic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-verify-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(Collections.singletonList(dlqTopic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(500));
                if (!recs.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
