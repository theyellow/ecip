# Design: Rewire moderation-service off `telegram.raw.messages` (#34)

**Date:** 2026-06-05
**Status:** Approved
**Size:** M

---

## Problem

`moderation-service` currently consumes `telegram.raw.messages` and runs keyword/regex rules against raw, unclassified message text. This means:

- Moderation fires before intent classification has happened — no context about what the message means
- Keyword rules can flag innocuous messages that would otherwise be classified as GREETING or THANKS
- An extra parallel consumer on `telegram.raw.messages` with no dependency on the upstream pipeline

## Goal

Move moderation-service to consume `policies.decisions` so that keyword/regex rules run after the message has been classified and a policy decision has been made. Moderation gains access to both the classified intent and the original message text.

---

## Architecture

### Current topology

```
telegram.raw.messages ──► intent-classifier ──► messages.classified ──► policy-engine ──► policies.decisions
         │
         └──► moderation-service (keyword/regex) ──► moderation.flags
```

### Target topology

```
telegram.raw.messages ──► intent-classifier ──► messages.classified ──► policy-engine ──► policies.decisions
                                                                                                │
                                                                              moderation-service (keyword/regex)
                                                                                                │
                                                                                       moderation.flags
```

---

## Changes

### 1. `PolicyDecisionEvent` — emcip-core

Add a top-level `messageText` field (String, nullable) to carry the original Telegram message text through to downstream consumers.

Schema version remains `1.0.0` — this is an additive field. Existing consumers that do not reference `messageText` are unaffected (Jackson ignores unknown fields by default).

```java
// PolicyDecisionEvent.java (emcip-core)
private String messageText;   // original Telegram message text — new field
```

### 2. `PolicyEvaluationService` — emcip-policy-engine

When building `PolicyDecisionEvent`, extract `messageText` from the `IntentClassifiedEvent.parameters` map and set it on the outgoing event.

`IntentClassifiedEvent.parameters` already carries `messageText` (populated by intent-classifier from the original `TelegramMessageEvent`). No upstream changes needed.

```java
String messageText = (String) event.getParameters().get("messageText");
PolicyDecisionEvent decision = PolicyDecisionEvent.builder()
    // ... existing fields ...
    .messageText(messageText)
    .build();
```

### 3. moderation-service

**Replace** `ModerationEventConsumer` (consumes `telegram.raw.messages`, receives `TelegramMessageEvent`) with `PolicyDecisionConsumer` (consumes `policies.decisions`, receives `PolicyDecisionEvent`).

`RuleEvaluationService` signature changes from:
```java
public Optional<ModerationFlagEvent> evaluate(TelegramMessageEvent event)
```
to:
```java
public Optional<ModerationFlagEvent> evaluate(String messageText, String sourceEventId)
```

This is a simplification — the service only ever needed `messageText` for regex matching and `eventId` for the output event. Decoupling it from `TelegramMessageEvent` makes it reusable against any text source.

**Output unchanged:** `ModerationFlagEvent` → `moderation.flags`. `sourceEventId` maps from `PolicyDecisionEvent.sourceEventId` (the original Telegram message event ID, unchanged through the pipeline).

**Tenant propagation:** unchanged — tenant ID is read from Kafka message headers, as with all other consumers.

**DLQ / retry config:** unchanged — same `CommonKafkaConfig` exponential backoff pattern.

### 4. Kafka topic flow diagram

`documentation/diagrams/kafka-topic-flow.puml`:
- Remove `moderation-service` from the `telegram.raw.messages` consumer list
- Add `moderation-service` as a consumer of `policies.decisions`
- Add `[enriched: +messageText]` annotation to `PolicyDecisionEvent`

---

## What does NOT change

| Component | Status |
|-----------|--------|
| `moderation.flags` topic | Unchanged |
| `ModerationFlagEvent` schema | Unchanged |
| audit-service | No changes |
| llm-orchestrator | No changes (separate backlog item) |
| conversation-context | No changes |
| intent-classifier | No changes |
| DLQ / retry topology | No changes |

---

## Out of scope

- `policies.decisions → llm-orchestrator` coupling redesign (separate backlog item)
- Merging `moderation.flags` with `moderation.actions` (no consumers exist yet for either)
- Changes to moderation rule storage or `RuleEvaluationService` matching logic

---

## Testing

- Unit test `PolicyDecisionConsumer`: mock `PolicyDecisionEvent` with matching and non-matching `messageText`; assert `ModerationFlagEvent` produced / not produced
- Unit test `RuleEvaluationService` with new signature: existing test cases adapted to pass `messageText` and `sourceEventId` directly
- Integration test: publish a `PolicyDecisionEvent` with matching text to `policies.decisions`; verify `ModerationFlagEvent` lands on `moderation.flags`
