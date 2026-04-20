---
name: kafka-messaging
description: Kafka producers, consumers, and DLQ handling with Spring Kafka
triggers:
  - "kafka"
  - "producer"
  - "consumer"
  - "DLQ"
  - "topic"
  - "bootstrap-servers"
  - "@KafkaListener"
---

# Kafka Messaging

## Configuration

### Basic KafkaConfig
```java
@EnableKafka
@Configuration
public class KafkaConfig {
    
    @Value("${spring.kafka.bootstrap-servers:localhost:14003}")
    private String bootstrapServers;
    
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "my-service");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }
}
```

## Consumer Implementation

### Standard Consumer
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MyEventConsumer {
    
    private final ObjectMapper objectMapper;
    private final MyService myService;
    
    @KafkaListener(topics = "my.topic", groupId = "my-service")
    public void handleEvent(String message) {
        log.info("Received event from my.topic: {}", message);
        
        try {
            MyEvent event = objectMapper.readValue(message, MyEvent.class);
            myService.process(event);
        } catch (Exception e) {
            log.error("Failed to process event: {}", message, e);
            throw new RuntimeException(e);  // Triggers retry/DLQ
        }
    }
}
```

### DLQ-Aware Consumer (Using emcip-core)
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MyRetryableConsumer extends RetryableKafkaListener {
    
    private final ObjectMapper objectMapper;
    private final MyService myService;
    
    @Override
    protected void processMessage(String topic, String message) {
        log.info("Processing event from {}: {}", topic, message);
        
        try {
            MyEvent event = objectMapper.readValue(message, MyEvent.class);
            myService.process(event);
        } catch (JsonProcessingException e) {
            throw new NonRetryableException("Invalid JSON", e);  // Goes to DLQ immediately
        }
    }
    
    @Override
    protected void onMaxRetriesExceeded(String topic, String message, Exception lastException) {
        log.error("Max retries exceeded for message: {}", message, lastException);
        // Additional alerting can be added here
    }
}
```

## Producer Implementation

### Standard Producer
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MyEventProducer {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public void sendEvent(MyEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("my.topic", event.getId(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send event: {}", event.getId(), ex);
                    } else {
                        log.info("Sent event to {} partition {}", 
                            "my.topic", 
                            result.getRecordMetadata().partition());
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
            throw new RuntimeException(e);
        }
    }
}
```

## Testing Kafka

### Test Configuration
```yaml
# src/test/resources/application-test.yml
spring:
  kafka:
    listener:
      auto-startup: false  # Prevent connection attempts
    bootstrap-servers: localhost:14003
```

### Testcontainers Setup
```java
@TestConfiguration
public class TestKafkaConfig {
    
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("bootstrap.servers", "localhost:14003");
        return new KafkaAdmin(configs);
    }
}
```

## Critical Rules

1. **ALWAYS** use port **14003** (not 9092 or 29092)
2. **ALWAYS** log events received/sent with topic and partition
3. **USE** `RetryableKafkaListener` from emcip-core for retry logic
4. **NEVER** catch and swallow exceptions in consumers - let them propagate for DLQ
5. **ALWAYS** handle JSON serialization errors explicitly
6. **USE** `NonRetryableException` for permanent failures (immediate DLQ)

## Port Information

| Environment | Port | Notes |
|-------------|------|-------|
| Docker Compose | 14003 | External listener for apps |
| Internal Docker | 14002 | Container-to-container |
| Testcontainers | 14003 | Matches docker-compose config |

## Related Topics
- `spring-boot-jpa` - Service layer integration
- `.claude/CLAUDE.md` - Current phase status (Epic 3.3 complete)
- `emcip-core DeadLetterTopicHandler` - DLQ operations
