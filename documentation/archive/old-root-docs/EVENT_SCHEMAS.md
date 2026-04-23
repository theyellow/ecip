# Event Schemas and Kafka Topics

This document defines the Kafka topics and event schemas for the EMCIP platform.

## Overview

- **Format:** JSON (no Schema Registry in Phase 1)
- **Versioning:** Semantic versioning (1.0.0, 1.1.0, 2.0.0)
- **Serialization:** JSON strings with UTF-8 encoding
- **Schema Evolution:** Additive changes only in minor versions

## Kafka Topics

| Topic | Partitions | Replication | Description |
|-------|------------|-------------|-------------|
| `telegram.raw.messages` | 3 | 1 | Raw messages from Telegram TDLib |
| `telegram.raw.updates` | 3 | 1 | Raw update events from Telegram |
| `messages.classified` | 3 | 1 | Messages with intent classification |
| `context.threads` | 3 | 1 | Thread/conversation context updates |
| `policies.decisions` | 3 | 1 | Policy engine decisions |
| `responses.generated` | 3 | 1 | LLM-generated responses |
| `moderation.flags` | 3 | 1 | Content moderation flags |
| `audit.events` | 3 | 1 | Audit trail events |

## Event Schemas

### 1. TelegramRawMessageEvent

**Topic:** `telegram.raw.messages`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {
      "type": "string",
      "format": "uuid",
      "description": "Unique event identifier"
    },
    "eventType": {
      "type": "string",
      "enum": ["TelegramRawMessageEvent"],
      "description": "Event type discriminator"
    },
    "version": {
      "type": "string",
      "pattern": "^\\d+\\.\\d+\\.\\d+$",
      "description": "Schema version (semver)"
    },
    "timestamp": {
      "type": "string",
      "format": "date-time",
      "description": "Event creation timestamp (ISO 8601)"
    },
    "source": {
      "type": "string",
      "description": "Source service (e.g., 'emcip-tdlib-adapter')"
    },
    "tenantId": {
      "type": "string",
      "description": "Tenant identifier (for multi-tenancy, Phase 5)"
    },
    "payload": {
      "type": "object",
      "required": ["messageId", "chatId", "senderId", "content", "timestamp"],
      "properties": {
        "messageId": {
          "type": "integer",
          "description": "Telegram message ID"
        },
        "chatId": {
          "type": "integer",
          "description": "Telegram chat/group ID"
        },
        "chatType": {
          "type": "string",
          "enum": ["private", "group", "channel", "supergroup"],
          "description": "Type of chat"
        },
        "senderId": {
          "type": "integer",
          "description": "Telegram user ID of sender"
        },
        "senderUsername": {
          "type": "string",
          "description": "Telegram username of sender"
        },
        "content": {
          "type": "object",
          "properties": {
            "text": {
              "type": "string",
              "description": "Message text content"
            },
            "type": {
              "type": "string",
              "enum": ["text", "photo", "video", "document", "audio", "other"],
              "description": "Message content type"
            }
          }
        },
        "timestamp": {
          "type": "string",
          "format": "date-time",
          "description": "Original message timestamp"
        },
        "isReply": {
          "type": "boolean",
          "description": "Whether message is a reply"
        },
        "replyToMessageId": {
          "type": "integer",
          "description": "Message ID being replied to"
        }
      }
    }
  }
}
```

**Example:**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "TelegramRawMessageEvent",
  "version": "1.0.0",
  "timestamp": "2026-04-15T14:30:00Z",
  "source": "emcip-tdlib-adapter",
  "tenantId": "tenant-001",
  "payload": {
    "messageId": 12345,
    "chatId": -1001234567890,
    "chatType": "supergroup",
    "senderId": 987654321,
    "senderUsername": "john_doe",
    "content": {
      "text": "Hello everyone!",
      "type": "text"
    },
    "timestamp": "2026-04-15T14:29:58Z",
    "isReply": false
  }
}
```

### 2. IntentClassifiedEvent

**Topic:** `messages.classified`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {"type": "string", "format": "uuid"},
    "eventType": {"type": "string", "enum": ["IntentClassifiedEvent"]},
    "version": {"type": "string", "pattern": "^\\d+\\.\\d+\\.\\d+$"},
    "timestamp": {"type": "string", "format": "date-time"},
    "source": {"type": "string"},
    "tenantId": {"type": "string"},
    "correlationId": {
      "type": "string",
      "description": "ID linking to original TelegramRawMessageEvent"
    },
    "payload": {
      "type": "object",
      "required": ["messageId", "chatId", "classifiedIntents", "confidence"],
      "properties": {
        "messageId": {"type": "integer"},
        "chatId": {"type": "integer"},
        "classifiedIntents": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "intent": {
                "type": "string",
                "enum": ["GREETING", "QUESTION", "COMMAND", "SPAM", "MENTION", "REPORT", "OTHER"]
              },
              "confidence": {
                "type": "number",
                "minimum": 0,
                "maximum": 1
              },
              "parameters": {
                "type": "object",
                "description": "Extracted parameters for the intent"
              }
            }
          }
        },
        "primaryIntent": {
          "type": "string",
          "description": "Highest confidence intent"
        },
        "confidence": {
          "type": "number",
          "minimum": 0,
          "maximum": 1
        },
        "requiresPolicyCheck": {
          "type": "boolean",
          "description": "Whether policy engine should evaluate"
        }
      }
    }
  }
}
```

### 3. PolicyDecisionEvent

**Topic:** `policies.decisions`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {"type": "string", "format": "uuid"},
    "eventType": {"type": "string", "enum": ["PolicyDecisionEvent"]},
    "version": {"type": "string"},
    "timestamp": {"type": "string", "format": "date-time"},
    "source": {"type": "string"},
    "tenantId": {"type": "string"},
    "correlationId": {"type": "string"},
    "payload": {
      "type": "object",
      "required": ["decisionId", "messageId", "chatId", "decision", "rulesTriggered"],
      "properties": {
        "decisionId": {"type": "string", "format": "uuid"},
        "messageId": {"type": "integer"},
        "chatId": {"type": "integer"},
        "decision": {
          "type": "string",
          "enum": ["ALLOW", "BLOCK", "FLAG", "RESPOND", "ESCALATE"]
        },
        "rulesTriggered": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "ruleId": {"type": "string"},
              "ruleName": {"type": "string"},
              "severity": {"type": "string", "enum": ["INFO", "WARNING", "CRITICAL"]}
            }
          }
        },
        "requiresHumanReview": {"type": "boolean"},
        "suggestedAction": {
          "type": "string",
          "enum": ["NONE", "DELETE", "WARN", "BAN", "RESPOND"]
        },
        "responseTemplate": {
          "type": "string",
          "description": "Template for LLM response (if applicable)"
        }
      }
    }
  }
}
```

### 4. ResponseGeneratedEvent

**Topic:** `responses.generated`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {"type": "string", "format": "uuid"},
    "eventType": {"type": "string", "enum": ["ResponseGeneratedEvent"]},
    "version": {"type": "string"},
    "timestamp": {"type": "string", "format": "date-time"},
    "source": {"type": "string"},
    "tenantId": {"type": "string"},
    "correlationId": {"type": "string"},
    "payload": {
      "type": "object",
      "required": ["responseId", "messageId", "chatId", "responseText", "modelUsed"],
      "properties": {
        "responseId": {"type": "string", "format": "uuid"},
        "messageId": {"type": "integer"},
        "chatId": {"type": "integer"},
        "responseText": {"type": "string"},
        "modelUsed": {"type": "string", "enum": ["MiniMax-2.7", "Claude", "Other"]},
        "tokenCount": {"type": "integer"},
        "costEstimate": {"type": "number"},
        "responseType": {
          "type": "string",
          "enum": ["ANSWER", "SUMMARY", "MODERATION_NOTICE", "GREETING"]
        },
        "requiresModeration": {"type": "boolean"}
      }
    }
  }
}
```

### 5. ModerationFlagEvent

**Topic:** `moderation.flags`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {"type": "string", "format": "uuid"},
    "eventType": {"type": "string", "enum": ["ModerationFlagEvent"]},
    "version": {"type": "string"},
    "timestamp": {"type": "string", "format": "date-time"},
    "source": {"type": "string"},
    "tenantId": {"type": "string"},
    "correlationId": {"type": "string"},
    "payload": {
      "type": "object",
      "required": ["flagId", "messageId", "chatId", "violationType", "severity"],
      "properties": {
        "flagId": {"type": "string", "format": "uuid"},
        "messageId": {"type": "integer"},
        "chatId": {"type": "integer"},
        "violationType": {
          "type": "string",
          "enum": ["SPAM", "HARASSMENT", "HATE_SPEECH", "INAPPROPRIATE_CONTENT", "SCAM", "OTHER"]
        },
        "severity": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"]},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "actionTaken": {
          "type": "string",
          "enum": ["NONE", "WARNED", "DELETED", "USER_BANNED", "REPORTED"]
        },
        "reviewedBy": {"type": "string", "description": "Admin ID if human-reviewed"}
      }
    }
  }
}
```

### 6. AuditEvent

**Topic:** `audit.events`  
**Version:** 1.0.0

```json
{
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "payload"],
  "properties": {
    "eventId": {"type": "string", "format": "uuid"},
    "eventType": {"type": "string", "enum": ["AuditEvent"]},
    "version": {"type": "string"},
    "timestamp": {"type": "string", "format": "date-time"},
    "source": {"type": "string"},
    "tenantId": {"type": "string"},
    "correlationId": {"type": "string"},
    "payload": {
      "type": "object",
      "required": ["action", "actor", "resource", "outcome"],
      "properties": {
        "action": {
          "type": "string",
          "enum": ["MESSAGE_RECEIVED", "INTENT_CLASSIFIED", "POLICY_EVALUATED", "RESPONSE_GENERATED", "MODERATION_APPLIED", "USER_BANNED", "RULE_CREATED", "RULE_MODIFIED"]
        },
        "actor": {
          "type": "object",
          "properties": {
            "type": {"type": "string", "enum": ["SYSTEM", "USER", "SERVICE"]},
            "id": {"type": "string"},
            "serviceName": {"type": "string"}
          }
        },
        "resource": {
          "type": "object",
          "properties": {
            "type": {"type": "string"},
            "id": {"type": "string"}
          }
        },
        "outcome": {"type": "string", "enum": ["SUCCESS", "FAILURE", "PARTIAL"]},
        "details": {"type": "object"},
        "processingTimeMs": {"type": "integer"}
      }
    }
  }
}
```

## Common Event Wrapper

All events share a common wrapper structure:

```json
{
  "eventId": "uuid",
  "eventType": "EventName",
  "version": "1.0.0",
  "timestamp": "ISO8601",
  "source": "service-name",
  "tenantId": "tenant-id",
  "correlationId": "uuid-of-original-event",
  "payload": { }
}
```

## Topic Naming Convention

- `{domain}.{action}` format
- Lowercase with dots as separators
- Domain: telegram, messages, context, policies, responses, moderation, audit
- Action: descriptive verb/noun

## Schema Evolution Strategy

1. **Backward Compatible Changes (PATCH):**
   - Add optional fields
   - Add new enum values
   - Relax constraints

2. **Non-Breaking Changes (MINOR):**
   - Add new required fields with defaults
   - Deprecate fields (keep in schema)

3. **Breaking Changes (MAJOR):**
   - Remove fields
   - Change field types
   - Add new required fields without defaults

## Next Steps

- Phase 2: Implement actual Kafka producers/consumers
- Phase 2: Add schema validation
- Phase 4: Consider Schema Registry for production
