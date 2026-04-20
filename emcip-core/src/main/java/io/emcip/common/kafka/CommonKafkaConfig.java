package io.emcip.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Common Kafka configuration for all EMCIP services. Provides enhanced error handling and retry
 * capabilities.
 */
@Configuration
@EnableKafka
public class CommonKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:14003}")
    private String bootstrapServers;

    @Value("${kafka.consumer.max-retries:3}")
    private int maxRetries;

    @Value("${kafka.consumer.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Bean
    public ObjectMapper kafkaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                StringDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Container factory with retry and DLQ support. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            DeadLetterTopicHandler dlqHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(createRetryableErrorHandler(dlqHandler));

        return factory;
    }

    /** Create error handler with exponential backoff and DLQ support. */
    private CommonErrorHandler createRetryableErrorHandler(DeadLetterTopicHandler dlqHandler) {
        // Exponential backoff: 1s, 2s, 4s, 8s
        ExponentialBackOff backOff = new ExponentialBackOff(retryDelayMs, 2.0);
        backOff.setMaxInterval(30000); // Max 30 seconds between retries

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        (record, exception) -> {
                            // After retries exhausted, send to DLQ
                            if (record != null) {
                                @SuppressWarnings("unchecked")
                                ConsumerRecord<String, String> typedRecord =
                                        (ConsumerRecord<String, String>) record;
                                dlqHandler.sendToDeadLetterQueue(
                                        typedRecord,
                                        exception,
                                        maxRetries,
                                        "unknown-group" // Will be overridden by listener
                                        );
                            }
                        },
                        backOff);

        // Configure retryable exceptions
        errorHandler.addRetryableExceptions(
                org.springframework.kafka.listener.ListenerExecutionFailedException.class,
                java.net.ConnectException.class,
                java.util.concurrent.TimeoutException.class);

        // Configure non-retryable exceptions
        errorHandler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);

        return errorHandler;
    }

    /** Container factory for DLQ monitoring (no retries, just monitoring). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dlqListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
