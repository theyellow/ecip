# Audit Service Schema

This document describes the database schema managed by `emcip-audit-service`.

Schema source:
`emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`

---

## Tables

### `audit_events`

Stores one row per auditable event received from any service in the platform.
Events arrive via Kafka and are persisted synchronously by the audit service.

| Column               | Type                     | Nullable | Description |
|----------------------|--------------------------|----------|-------------|
| `id`                 | `BIGSERIAL`              | No       | Surrogate primary key. Auto-incremented by PostgreSQL. |
| `event_id`           | `VARCHAR(100)`           | No       | Unique identifier supplied by the producing service (idempotency key). |
| `event_type`         | `VARCHAR(50)`            | No       | Logical classification of the event (e.g. `MODERATION_FLAG`, `POLICY_DECISION`, `INTENT_CLASSIFIED`). |
| `correlation_id`     | `VARCHAR(100)`           | Yes      | Trace identifier linking multiple events to a single originating request or message. |
| `source_service`     | `VARCHAR(100)`           | No       | Name of the service that emitted the event (e.g. `emcip-moderation-service`). |
| `action`             | `VARCHAR(50)`            | No       | The operation that was performed (e.g. `FLAG`, `BLOCK`, `CLASSIFY`, `DECIDE`). |
| `actor_type`         | `VARCHAR(20)`            | No       | Category of the entity that triggered the action (`USER`, `SERVICE`, `SYSTEM`). |
| `actor_id`           | `VARCHAR(100)`           | Yes      | Identifier of the actor (user ID, service name, etc.). Null for anonymous or system-initiated actions. |
| `resource_type`      | `VARCHAR(50)`            | Yes      | Type of the resource that was acted on (e.g. `MESSAGE`, `GROUP`, `POLICY`). |
| `resource_id`        | `VARCHAR(100)`           | Yes      | Identifier of the specific resource instance. |
| `outcome`            | `VARCHAR(20)`            | No       | Result of the action (`SUCCESS`, `FAILURE`, `SKIPPED`). |
| `details`            | `JSONB`                  | Yes      | Flexible payload for event-specific metadata. Schema varies by `event_type`. |
| `processing_time_ms` | `INTEGER`                | Yes      | Time in milliseconds taken to process the event in the source service. |
| `created_at`         | `TIMESTAMP`              | No       | Wall-clock time when the event was recorded. Defaults to `NOW()`. |

#### Notes

- `event_id` carries a `UNIQUE` constraint. Duplicate inserts (Kafka at-least-once redelivery) are
  rejected at the database level, making the audit consumer idempotent.
- `details` is `JSONB`, allowing event-specific data without schema migrations. Index on `details`
  fields can be added if query patterns demand it.
- `created_at` is the persistence timestamp, not the originating event timestamp. If the source
  service provides an event timestamp it should be stored inside `details`.

---

### `metrics_snapshots`

Stores periodic numeric metric readings from each service. These complement the
Prometheus/Actuator metrics endpoint with a queryable historical record.

| Column         | Type              | Nullable | Description |
|----------------|-------------------|----------|-------------|
| `id`           | `BIGSERIAL`       | No       | Surrogate primary key. |
| `service_name` | `VARCHAR(100)`    | No       | Name of the service that reported the metric. |
| `metric_name`  | `VARCHAR(100)`    | No       | Metric identifier (e.g. `messages.processed`, `rules.evaluated`). |
| `metric_value` | `DECIMAL(20,6)`   | No       | Numeric value of the metric at snapshot time. Supports counters, gauges, and rates. |
| `tags`         | `JSONB`           | Yes      | Key-value pairs for dimensional labelling (e.g. `{"topic": "moderation.flags", "partition": "0"}`). |
| `recorded_at`  | `TIMESTAMP`       | No       | Time of the snapshot. Defaults to `NOW()`. |

---

## Indexes

| Index Name                     | Table               | Columns                          | Rationale |
|-------------------------------|---------------------|----------------------------------|-----------|
| `idx_audit_events_type`       | `audit_events`      | `event_type`                     | Filtering and grouping events by type is the most common query pattern for dashboards and alerting rules. |
| `idx_audit_events_correlation`| `audit_events`      | `correlation_id`                 | Trace lookups join multiple events by correlation ID; without this index such queries would be full table scans. |
| `idx_audit_events_created`    | `audit_events`      | `created_at`                     | Time-range queries (last N hours, date-bucketed aggregations) and the retention cleanup job both filter by `created_at`. |
| `idx_metrics_service`         | `metrics_snapshots` | `service_name`, `recorded_at`    | Composite index covers the primary access pattern: "all metrics for service X in time range Y–Z". Column order matches query predicates. |

---

## Retention Policy

The default retention period is **90 days**. Events older than this threshold can be
purged from `audit_events` and `metrics_snapshots` without affecting service operation.

The retention duration is configurable via:

```yaml
audit:
  retention:
    days: 90
```

A scheduled cleanup job in `emcip-audit-service` runs nightly and deletes rows where
`created_at < NOW() - INTERVAL '<days> days'`.

To adjust the retention window, update the `audit.retention.days` property in
`emcip-audit-service/src/main/resources/application.yml` (or override via environment
variable `AUDIT_RETENTION_DAYS`) and restart the service.

---

## Example Queries

### Events in the last 24 hours

```sql
SELECT *
FROM audit_events
WHERE created_at >= NOW() - INTERVAL '24 hours'
ORDER BY created_at DESC;
```

### Count of events by type (last 7 days)

```sql
SELECT event_type, COUNT(*) AS total
FROM audit_events
WHERE created_at >= NOW() - INTERVAL '7 days'
GROUP BY event_type
ORDER BY total DESC;
```

### All events for a correlation ID

```sql
SELECT id, event_type, source_service, action, outcome, created_at, details
FROM audit_events
WHERE correlation_id = 'your-correlation-id-here'
ORDER BY created_at ASC;
```

### Events with a specific outcome in a time range

```sql
SELECT source_service, event_type, COUNT(*) AS failures
FROM audit_events
WHERE outcome = 'FAILURE'
  AND created_at >= NOW() - INTERVAL '1 hour'
GROUP BY source_service, event_type
ORDER BY failures DESC;
```

### Latest metric snapshots per service

```sql
SELECT DISTINCT ON (service_name, metric_name)
    service_name,
    metric_name,
    metric_value,
    tags,
    recorded_at
FROM metrics_snapshots
ORDER BY service_name, metric_name, recorded_at DESC;
```

---

## Schema Source

Liquibase changeset: `1.0.0-audit-service-init`
File: `emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`
