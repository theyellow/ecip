# ADR-002: Event-Driven Architecture with Kafka

## Status

**Accepted**

Date: 2026-04-15

## Context

EMCIP processes messages from Telegram communities through multiple stages:
1. Message ingestion (TDLib adapter)
2. Intent classification
3. Policy evaluation
4. LLM response generation (if needed)
5. Context tracking
6. Audit logging

We need an architecture that:
- Decouples services for independent scaling
- Handles backpressure during traffic spikes
- Enables replay and event sourcing
- Supports real-time and batch processing

## Decision

We will implement an **Event-Driven Architecture (EDA)** using **Apache Kafka** as the event backbone.

### Key Design Choices

1. **Async Communication**: Services communicate via events, not direct HTTP calls
2. **Event Topics**: 8 core topics defined (see [EVENT_SCHEMAS.md](../../EVENT_SCHEMAS.md))
3. **JSON Serialization**: Phase 1 (Avro/Schema Registry in Phase 4)
4. **At-least-once Delivery**: With idempotent consumers

### Event Flow

```
Telegram → TDLib Adapter → telegram.raw.messages
                              ↓
                    Intent Classifier → messages.classified
                              ↓
                    Policy Engine → policies.decisions
                              ↓
                    [LLM Orchestrator → responses.generated]
                              ↓
                    [Moderation Service → moderation.flags]
                              ↓
                    Audit Service ← All events
```

### Topic Structure

| Topic | Purpose | Partitions |
|-------|---------|------------|
| telegram.raw.messages | Raw Telegram messages | 3 |
| messages.classified | Intent classification results | 3 |
| policies.decisions | Policy evaluation results | 3 |
| responses.generated | LLM-generated responses | 3 |
| moderation.flags | Content moderation events | 3 |
| audit.events | Audit trail | 3 |

## Consequences

### Positive
- Loose coupling between services
- Independent scaling of pipeline stages
- Natural replay capability for debugging
- Event sourcing foundation for audit
- Backpressure handling via Kafka

### Negative
- Eventual consistency challenges
- Debugging complexity (distributed traces needed)
- Schema evolution management required
- Operational complexity of Kafka cluster

## Alternatives Considered

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| RabbitMQ | Simple, good for task queues | Less scalable, no replay | Rejected |
| Pulsar | Cloud-native, multi-tenant | Smaller ecosystem, newer | Rejected |
| NATS | Fast, simple | Less durable, smaller ecosystem | Rejected |
| HTTP/gRPC | Simple, synchronous | Tight coupling, blocking | Rejected |

## References

- [EVENT_SCHEMAS.md](../../EVENT_SCHEMAS.md) - Event definitions
- [docker-compose.yml](../../docker-compose.yml) - Kafka setup
- [US-1.3.2](../planning/phases/PHASE-1_USER_STORIES.md) - Event schemas user story

## Notes

We chose JSON over Avro for Phase 1 to simplify development. Schema Registry will be introduced in Phase 4 for production hardening.
