package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ModerationFlowIT extends AbstractModerationIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private ModerationRuleRepository ruleRepository;

    @Test
    void telegramMessage_matchingKeywordRule_producesModerationFlagEvent() throws Exception {
        // Arrange: insert an enabled keyword rule with retry for flaky DB connections
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
                        .tenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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

        // Arrange: build input event
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-mod-flow-001",
                        Instant.now().toString(),
                        null,
                        null,
                        100L,
                        200L,
                        "user-mod-1",
                        "USER",
                        "this message contains spam_it_test_keyword_99",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        null);
        String json = new ObjectMapper().writeValueAsString(event);

        // Arrange: subscribe to output topic with a unique group to capture from the start
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

        // Act: publish input event
        kafkaTemplate.send("telegram.raw.messages", "evt-mod-flow-001", json).get();

        // Assert: ModerationFlagEvent appears on output topic within 15 seconds
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
