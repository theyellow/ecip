package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ModerationFlowIT extends AbstractModerationIntegrationTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private ModerationRuleRepository ruleRepository;

    @Test
    void policyDecision_matchingKeywordRule_producesModerationFlagEvent() throws Exception {
        // Arrange: insert an enabled keyword rule
        ModerationRule rule =
                ModerationRule.builder()
                        .name("spam-detection-it")
                        .ruleType("KEYWORD")
                        .pattern("spam_it_test_keyword_99")
                        .severity("HIGH")
                        .action("FLAG")
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .tenantId(UUID.fromString(TENANT_ID))
                        .build();

        await().atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(
                        () -> {
                            try {
                                ruleRepository.save(rule).block();
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Arrange: build PolicyDecisionEvent with matching messageText
        PolicyDecisionEvent event =
                new PolicyDecisionEvent(
                        "evt-mod-flow-001",
                        Instant.now().toString(),
                        null,
                        null,
                        "evt-mod-flow-001",
                        "policy-001",
                        "BLOCK",
                        "Spam detected",
                        Map.of(
                                "originalIntent",
                                "SPAM",
                                "confidence",
                                0.95,
                                "matchedRules",
                                List.of()),
                        List.of("block"),
                        "this message contains spam_it_test_keyword_99");
        String json = new ObjectMapper().writeValueAsString(event);

        // Arrange: subscribe to output topic
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "test-mod-flow-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Consumer<String, String> testConsumer =
                new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        testConsumer.subscribe(Collections.singletonList("moderation.flags"));

        // Act: publish to policies.decisions with tenant header
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>("policies.decisions", "evt-mod-flow-001", json);
        producerRecord.headers().add("tenant_id", TENANT_ID.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(producerRecord).get();

        // Assert: ModerationFlagEvent appears on moderation.flags within 15 seconds
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, String> records =
                                    testConsumer.poll(Duration.ofMillis(500));
                            assertThat(records.count()).isGreaterThan(0);
                            String value = records.iterator().next().value();
                            assertThat(value).contains("ModerationFlag");
                            assertThat(value).contains("evt-mod-flow-001");
                        });

        testConsumer.close();
    }
}
