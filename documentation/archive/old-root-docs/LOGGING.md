# EMCIP Logging Standards

## Overview

All EMCIP services use **Lombok** for boilerplate code reduction and **Slf4j** for logging. This document defines the mandatory logging standards for the project.

## Lombok Requirements

### Required Annotations

#### 1. @Slf4j (Logging)
**MUST** be used on every class that needs logging.

```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MyService {
    public void doSomething() {
        log.info("Doing something important");
    }
}
```

**NEVER** use manual Logger instantiation:
```java
// WRONG - Don't do this!
private static final Logger logger = LoggerFactory.getLogger(MyService.class);
```

#### 2. @Getter / @Setter (Entities)
**MUST** be used on all JPA entity classes.

```java
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "messages")
public class Message {
    @Id
    private Long id;
    private String textContent;
    // Getters and setters generated automatically
}
```

**NEVER** write manual getters/setters:
```java
// WRONG - Don't do this!
public String getTextContent() {
    return textContent;
}
public void setTextContent(String textContent) {
    this.textContent = textContent;
}
```

#### 3. @RequiredArgsConstructor (Dependency Injection)
**MUST** be used for constructor-based injection.

```java
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    // Constructor auto-generated with all final fields
}
```

**NEVER** use manual constructor:
```java
// WRONG - Don't do this!
@Autowired
public MessageService(MessageRepository repository, ...) {
    this.repository = repository;
    // ...
}
```

#### 4. @Builder (Complex Objects)
**SHOULD** be used for creating objects with many optional fields.

```java
import lombok.Builder;

@Builder
public class MessageEvent {
    private String eventId;
    private String text;
    private Long senderId;
    private Instant timestamp;
}

// Usage
MessageEvent event = MessageEvent.builder()
    .eventId(UUID.randomUUID().toString())
    .text("Hello")
    .senderId(123L)
    .timestamp(Instant.now())
    .build();
```

## Logging Standards

### Log Levels

| Level | Use Case | Example |
|-------|----------|---------|
| **ERROR** | Unexpected failures that need immediate attention | `log.error("Failed to send Kafka message", e);` |
| **WARN** | Suspicious situations that don't stop processing | `log.warn("High rate of spam messages detected");` |
| **INFO** | Important business events | `log.info("Message classified as {}", intent);` |
| **DEBUG** | Detailed information for troubleshooting | `log.debug("Processing message: {}", messageId);` |
| **TRACE** | Very detailed entry/exit information (rarely used) | `log.trace("Entering method processMessage");` |

### Mandatory Log Events

Every EMCIP service **MUST** log the following events:

#### 1. Kafka Events
```java
// When consuming from Kafka
log.info("Received message from topic {}: partition={}, offset={}", 
    topic, partition, offset);

// When producing to Kafka
log.info("Published message to topic {}: eventId={}", 
    topic, eventId);

// When Kafka processing fails
log.error("Failed to process Kafka message: {}", 
    record.value(), exception);
```

#### 2. Database Operations
```java
// When saving entity
log.debug("Saving {} with id {}", entityName, entityId);

// When entity found/not found
log.debug("Found {} with id {}", entityName, entityId);
log.warn("Entity {} with id {} not found", entityName, entityId);

// When database operation fails
log.error("Database error while saving {}: {}", 
    entityName, exception.getMessage());
```

#### 3. Authentication Events
```java
// When user logs in
log.info("User {} logged in successfully", userId);

// When authentication fails
log.warn("Authentication failed for user {}: {}", 
    userId, reason);

// When 2FA is used
log.info("2FA verification requested for user {}", userId);
```

#### 4. Policy Decisions
```java
// When policy is evaluated
log.info("Policy {} evaluated: decision={} for event {}", 
    policyId, decision, eventId);

// When action is taken
log.info("Action {} executed for policy decision", action);
```

#### 5. Classification Events
```java
// When message is classified
log.info("Message {} classified as {} with confidence {}", 
    messageId, intent, confidence);

// When classification confidence is low
log.warn("Low confidence classification: {} ({})", 
    intent, confidence);
```

#### 6. Errors
```java
// Always include exception for stack traces in error logs
log.error("Unexpected error processing message {}: {}", 
    messageId, exception.getMessage(), exception);

// For expected exceptions (validation, etc.)
log.warn("Validation failed for event {}: {}", 
    eventId, validationErrors);
```

### Logging Best Practices

#### 1. Use Parameterized Logging
**GOOD:**
```java
log.info("Processing message {} from user {}", messageId, userId);
```

**BAD:**
```java
log.info("Processing message " + messageId + " from user " + userId);
```

#### 2. Don't Log Sensitive Data
**NEVER** log:
- Passwords
- API keys
- Private keys
- Personal identification numbers
- Full message content in production (use IDs instead)

```java
// BAD - Don't do this!
log.info("User logged in with password: {}", password);

// GOOD
log.info("User {} logged in successfully", userId);
```

#### 3. Include Context
Always include enough context to understand the log:

```java
// BAD - Not enough context
log.info("Processing complete");

// GOOD - Full context
log.info("Message classification complete: messageId={}, intent={}, confidence={}",
    messageId, intent, confidence);
```

#### 4. Use Appropriate Log Levels
- **ERROR**: Something is broken and needs fixing now
- **WARN**: Something unexpected happened but we recovered
- **INFO**: Normal business operation that we want to track
- **DEBUG**: Detailed information for development/troubleshooting

#### 5. Correlation IDs
Include correlation IDs for distributed tracing:

```java
log.info("[{}] Processing message {}", 
    MDC.get("correlationId"), messageId);
```

### Log Configuration

Add to your `application.yml`:

```yaml
logging:
  level:
    root: INFO
    io.emcip: DEBUG
    org.springframework.kafka: WARN
    org.apache.kafka: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### Structured Logging (JSON)

For production, configure JSON logging:

```yaml
logging:
  pattern:
    console: "{\"timestamp\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ}\",\"level\":\"%p\",\"logger\":\"%logger{36}\",\"message\":\"%msg\"}%n"
```

## IDE Setup

### IntelliJ IDEA
1. Install **Lombok Plugin** (should be bundled in recent versions)
2. Enable annotation processing:
   - Settings → Build → Compiler → Annotation Processors
   - Check "Enable annotation processing"
3. Install **Slf4j** plugin for log level highlighting

### VS Code
Install extensions:
- **Lombok Annotations Support**
- **Extension Pack for Java**

## Verification Checklist

Before committing code:

- [ ] All classes needing logs have `@Slf4j`
- [ ] All entities have `@Getter` and `@Setter`
- [ ] All services use `@RequiredArgsConstructor`
- [ ] No manual getters/setters in entities
- [ ] No manual LoggerFactory usage
- [ ] All Kafka operations are logged
- [ ] All database operations are logged
- [ ] All authentication events are logged
- [ ] All errors include exception details
- [ ] Sensitive data is NOT logged
- [ ] Using parameterized logging format
