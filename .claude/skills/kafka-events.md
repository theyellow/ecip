---
name: kafka-events
description: Spring Kafka producer and consumer patterns for EMCIP
triggers:
  - "kafka"
  - "producer"
  - "consumer"
  - "topic"
  - "event"
  - "@KafkaListener"
---

## Kafka Event Patterns

### Producer Configuration

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(config);
    }
    
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

### Reactive Producer Service

```java
@Service
public class EventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }
    
    public Mono<Void> publishMessageClassified(MessageClassifiedEvent event) {
        return Mono.fromFuture(
            kafkaTemplate.send("messages.classified", 
                event.messageId().toString(), 
                serialize(event))
            .toCompletableFuture()
        )
        .doOnSuccess(result -> log.info("Published: {}", event.messageId()))
        .doOnError(e -> log.error("Failed to publish: {}", e.getMessage()))
        .onErrorResume(e -> Mono.empty())
        .then();
    }
    
    public Mono<Void> publishPolicyDecision(PolicyDecisionEvent event) {
        return Mono.fromFuture(
            kafkaTemplate.send("policies.decisions",
                event.decisionId().toString(),
                serialize(event))
            .toCompletableFuture()
        )
        .then();
    }
    
    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new SerializationException(e);
        }
    }
}
```

### Consumer Configuration

```java
@Configuration
public class KafkaConsumerConfig {
    
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "intent-classifier-group");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> 
            kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // 3 consumers per instance
        factory.getContainerProperties().setAckMode(
            ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
```

### Reactive Consumer

```java
@Component
public class MessageEventConsumer {
    private final IntentClassificationService service;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    
    public MessageEventConsumer(IntentClassificationService service,
                               ObjectMapper objectMapper,
                               AuditService auditService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }
    
    @KafkaListener(topics = "telegram.raw.messages", groupId = "intent-classifier")
    public Mono<Void> handleRawMessage(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment) {
        
        return deserialize(record.value(), TelegramRawMessageEvent.class)
            .flatMap(service::classify)
            .flatMap(result -> publishClassification(result)
                .then(auditService.recordClassification(result)))
            .doOnSuccess(v -> acknowledgment.acknowledge())
            .doOnError(e -> log.error("Failed to process message: {}", 
                record.key(), e))
            .onErrorResume(e -> {
                // Don't acknowledge - message will be retried
                return Mono.empty();
            });
    }
    
    private <T> Mono<T> deserialize(String json, Class<T> clazz) {
        return Mono.fromCallable(() -> 
            objectMapper.readValue(json, clazz))
            .subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<Void> publishClassification(ClassificationResult result) {
        // Publishing logic
        return Mono.empty();
    }
}
```

### Batch Consumer

```java
@Component
public class BatchAuditConsumer {
    private final AuditRepository auditRepository;
    
    public BatchAuditConsumer(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }
    
    @KafkaListener(topics = "audit.events", 
                   groupId = "audit-service",
                   batch = "true")
    public Mono<Void> handleBatch(List<ConsumerRecord<String, String>> records) {
        return Flux.fromIterable(records)
            .flatMap(this::toAuditEvent)
            .collectList()
            .flatMapMany(auditRepository::saveAll)
            .then();
    }
    
    private Mono<AuditEvent> toAuditEvent(ConsumerRecord<String, String> record) {
        return Mono.fromCallable(() -> 
            new AuditEvent(record.key(), record.value(), Instant.now()))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
```

### Event Schema Examples

```java
// TelegramRawMessageEvent
public record TelegramRawMessageEvent(
    UUID messageId,
    Long telegramMessageId,
    Long chatId,
    Long userId,
    String text,
    Instant timestamp,
    Map<String, Object> metadata
) {}

// IntentClassifiedEvent
public record IntentClassifiedEvent(
    UUID messageId,
    String intent,
    Double confidence,
    Map<String, Object> parameters,
    Instant classifiedAt
) {}

// PolicyDecisionEvent
public record PolicyDecisionEvent(
    UUID decisionId,
    UUID messageId,
    String policyId,
    String decision,
    String reason,
    Map<String, Object> context,
    Instant decidedAt
) {}
```

### Error Handling and DLQ

```java
@Component
public class DlqProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    public DlqProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }
    
    public Mono<Void> sendToDlq(String topic, ConsumerRecord<?, ?> record, 
                                 Throwable error) {
        FailedEvent failed = new FailedEvent(
            record.topic(),
            record.key(),
            record.value(),
            error.getMessage(),
            Instant.now()
        );
        
        return Mono.fromFuture(
            kafkaTemplate.send(topic + ".dlq", serialize(failed))
                .toCompletableFuture()
        ).then();
    }
}

@Component
public class SafeConsumer {
    private final DlqProducer dlqProducer;
    private final ObjectMapper mapper;
    
    public SafeConsumer(DlqProducer dlqProducer, ObjectMapper mapper) {
        this.dlqProducer = dlqProducer;
        this.mapper = mapper;
    }
    
    @KafkaListener(topics = "messages.classified")
    public Mono<Void> safeConsume(ConsumerRecord<String, String> record) {
        return process(record)
            .onErrorResume(e -> {
                log.error("Processing failed, sending to DLQ: {}", e.getMessage());
                return dlqProducer.sendToDlq("messages.classified", record, e);
            });
    }
    
    private Mono<Void> process(ConsumerRecord<String, String> record) {
        // Actual processing logic
        return Mono.empty();
    }
}
```

### Testing Kafka

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 1, topics = {"test.topic"})
class KafkaIntegrationTest {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    void testPublishAndConsume() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        
        // Setup consumer
        // ...
        
        // Publish
        kafkaTemplate.send("test.topic", "key", "value");
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("value", received.get());
    }
}
```
