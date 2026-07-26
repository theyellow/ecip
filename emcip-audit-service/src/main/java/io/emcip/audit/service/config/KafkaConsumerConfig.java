package io.emcip.audit.service.config;

import io.emcip.common.kafka.DeadLetterTopicHandler;
import io.emcip.common.kafka.KafkaMetricsConfig;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@EnableKafka
@Configuration
@Import(KafkaMetricsConfig.class)
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:14003}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:emcip-audit-service}")
    private String groupId;

    // --- Consumer ---

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    // --- Producer (used only for DLQ publishing) ---

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public DeadLetterTopicHandler deadLetterTopicHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaMetricsConfig metricsConfig) {
        return new DeadLetterTopicHandler(kafkaTemplate, objectMapper, metricsConfig);
    }

    // --- Listener container factory: manual ack + retry(backoff) -> DLQ ---

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            DeadLetterTopicHandler dlqHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler(dlqHandler));
        return factory;
    }

    private DefaultErrorHandler errorHandler(DeadLetterTopicHandler dlqHandler) {
        // 1s, 2s, 4s ... capped at 30s, give up after ~1 min -> recover to DLQ.
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(60_000L);

        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        (record, exception) -> {
                            @SuppressWarnings("unchecked")
                            ConsumerRecord<String, String> rec =
                                    (ConsumerRecord<String, String>) record;
                            // On retries-exhausted (or a non-retryable exception), park the record
                            // on <topic>.dlq so MANUAL_IMMEDIATE never commits past a lost record.
                            dlqHandler.sendToDeadLetterQueue(
                                    rec, exception.getMessage(), 0, groupId);
                        },
                        backOff);
        // Malformed payloads are permanent: recover (DLQ) immediately, don't waste retries.
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
