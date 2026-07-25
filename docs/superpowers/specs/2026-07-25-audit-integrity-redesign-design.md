# P2.1 — Audit Integrity Redesign

> Status: design (awaiting user review)
> Date: 2026-07-25
> Phase: P2.1 (`documentation/ROADMAP.md`)
> Closes: RT2-002, RT2-016, B1; fulfils RT-027's tamper-evident intent
> Module: `emcip-audit-service` (+ one new dependency)

## 1. Context

The audit hash chain (RT-027) was scaffolded but never activated. `audit_events` has
`integrity_hash` / `prev_hash` columns (changelog `003`) and `AuditService` exposes
`saveWithChain()` / `verifyChain()`, but `AuditEventConsumer` persists via `save()`, so **both
columns are NULL for every existing row** — the chain is inert and `AuditChainVerificationJob`
verifies nothing.

Three findings were deferred out of P1 because they are **coupled** and cannot be shipped
independently (see `ROADMAP.md` P1 note, 2026-07-22):

- **RT2-002 / B1** — activating `saveWithChain()` as-is forks the chain. It is an unsynchronised
  read-modify-write (read tail hash → compute → insert). The audit consumer runs **five separate
  `@KafkaListener` methods**, each with its own listener container; `KafkaConsumerConfig` sets
  `setConcurrency(3)`. Even at concurrency 1, five containers = up to five threads racing the same
  table tail, so two rows can claim the same predecessor. A forked chain makes
  `AuditChainVerificationJob` report tamper evidence on untampered data — the exact inverse of the
  goal. **`setConcurrency(1)` alone does not fix this.**
- **B1** — the listener does `auditService.save(entity).block()` under `AckMode.MANUAL_IMMEDIATE`.
  `.block()` was accidentally providing per-thread serialisation and retry-on-failure. Removing it
  naively, with per-record offset commits and no error handler, means a failed save whose successors
  succeed commits *past* the lost record → **silent audit-event loss**. audit-service defines its
  own `KafkaConsumerConfig` with **no error handler** and does not use `CommonKafkaConfig`.
- **RT2-016** — there is no DELETE-prevention trigger; the UPDATE-prevention trigger already exists
  (`003`). A DELETE trigger must not break the legitimate `AuditRetentionJob` purge.

## 2. Goals / non-goals

**Goals**
1. Activate the chain such that concurrent appends across all five topics — and any future replica —
   produce a single linear chain (no forks, no gaps).
2. Remove `.block()`: a fully reactive consumer with explicit retry → DLQ and no silent loss.
3. Make the chain genuinely tamper-evident (detect content tampering, not just broken pointers).
4. Add a DELETE-prevention trigger that still permits the sanctioned retention purge.

**Non-goals (explicit)**
- Migrating other services to reactive Kafka.
- Retroactively hashing pre-activation rows (they predate the feature; verification skips NULL-hash
  rows).
- audit-service multi-replica HA tuning (the advisory lock makes the chain *correct* under
  scale-out; replica counts / PDBs stay P4, item 12).

## 3. Design

Four coupled changes, one PR. **Do not split.**

### 3.1 (A) Serialise + activate the chain — `AuditService.saveWithChain`

Wrap read-tail → compute → insert in a single R2DBC transaction via `TransactionalOperator`
(Rule 5: no `@Transactional` on reactive), fronted by a cluster-wide Postgres advisory lock held
for the duration of the transaction:

```
BEGIN
  SELECT pg_advisory_xact_lock(:AUDIT_CHAIN_LOCK_KEY)   -- released automatically on COMMIT/ROLLBACK
  SELECT integrity_hash FROM audit_events ORDER BY id DESC LIMIT 1   -- tail (findTopByOrderByIdDesc)
  -- compute integrity_hash in Java (§3.3)
  INSERT INTO audit_events (...)
COMMIT
```

- `AUDIT_CHAIN_LOCK_KEY` — a single fixed `bigint` constant (documented derivation, e.g. a
  truncated hash of `"emcip.audit_chain"`), defined once in `AuditService`.
- `pg_advisory_xact_lock` is transaction-scoped, cluster-wide (one Postgres), and auto-released — no
  leak on failure, correct across replicas. It supersedes the in-process-lock class of bug noted for
  JWT revocation (P1-M2).
- The lock round-trip cost is acceptable: audit appends are inherently serial, and audit write volume
  is modest relative to a `pg_advisory_xact_lock` call.
- The consumer switches `save()` → `saveWithChain()`. The first post-activation row is genesis
  (`prev_hash = null`). Pre-activation NULL-hash rows are documented as predating the feature.

### 3.2 (B) Reactive consumer — replaces `AuditEventConsumer` + `KafkaConsumerConfig`

Add `io.projectreactor.kafka:reactor-kafka` (net-new to the repo; the standard Reactor-maintained
binding). Replace the five `@KafkaListener` methods and the bare factory with one reactive pipeline.

**Topic → mapper registry.** The five per-topic mappings currently inlined in the listener methods
(event class, `resourceIdFn`, `actorIdFn`, `correlationIdFn`, `detailsFn`, `sourceService`,
`resourceType`) are extracted into an immutable registry keyed by topic name — the same data, no
longer as methods. This is the SC5-style "extract generic handler" refactor applied reactively.

**Pipeline** (one `ReactiveKafkaConsumerTemplate` subscribed to all five topics):

```
receive()
  .concatMap(record -> handle(record))     // concatMap => in-instance serialisation, one at a time
  .subscribe();
```

`handle(record)`:
1. `TenantAwareKafkaSupport.validateTenantHeader(record)` — unchanged fail-closed behaviour,
   `GLOBAL_TENANT_SENTINEL` logic intact. On rejection, log + commit offset (skip). The tenant UUID
   is set on the **entity**, not a `TenantContext` ThreadLocal — this removes the last ThreadLocal
   use in the consumer, aligning with SC3/SC4.
2. Parse to the registry's event class; build `AuditEventEntity` via the registry's mapping.
3. `saveWithChain(entity)` (§3.1) → on success `record.receiverOffset().commit()`.
4. **Transient failure:** `.retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))` (mirrors the existing
   `CommonKafkaConfig` `ExponentialBackOff`). On exhaustion → `DeadLetterTopicHandler
   .sendToDeadLetterQueue(record, ...)` **then** commit the offset — the record is durably in
   `<topic>.dlq`, never lost, and the offset advances only after it lands there.
5. **Permanent failure** (malformed JSON / `JacksonException`) → classified non-retryable → straight
   to DLQ + commit. (Today these are silently dropped; routing to DLQ is an improvement.)

`concatMap` guarantees one-record-at-a-time processing within an instance; the advisory lock (§3.1)
covers cross-instance/replica serialisation. Together they make forks impossible without relying on
`.block()` timing.

### 3.3 (C) Tamper-evident hash + verification

**Hash formula** (defined at activation, applies to all post-activation rows):

```
integrity_hash = sha256( eventId | createdAt | eventType | actorId |
                         resourceType | resourceId | prev_hash )
```

Folding `prev_hash` into the digest means altering any earlier row cascades into every later
`integrity_hash`.

**`verifyChain`** is upgraded to genuine tamper-evidence. For each adjacent pair (newest → oldest),
skipping NULL-hash pre-activation rows:
1. **Recompute** `integrity_hash` from the row's stored content + stored `prev_hash`; compare to the
   stored `integrity_hash` → detects content tampering.
2. Check linkage: `newer.prev_hash == older.integrity_hash` → detects deletion/reordering.

`ChainVerificationResult` gains a failure-reason discriminator distinguishing **content-hash
mismatch** from **broken linkage** so `AuditChainVerificationJob`'s CRITICAL log says which.

### 3.4 (D) DELETE-prevention trigger + guarded retention

**Migration** `004-audit-delete-prevention.xml` (included in `db.changelog-master.xml`; no column
changes — hash columns exist from `003`):

```sql
CREATE OR REPLACE FUNCTION prevent_audit_delete() RETURNS trigger AS $$
BEGIN
  IF current_setting('emcip.audit_purge', true) IS DISTINCT FROM 'on' THEN
    RAISE EXCEPTION 'audit_events rows cannot be deleted';
  END IF;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_no_delete
  BEFORE DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_delete();
```

Rollback drops both trigger and function. `current_setting(..., true)` is missing-safe (returns NULL
when unset), so any un-flagged DELETE is blocked.

**Retention** — `AuditService.deleteRecordsOlderThan` runs inside a `TransactionalOperator`
transaction that first issues `SET LOCAL emcip.audit_purge = 'on'` (via `DatabaseClient`, same
connection/transaction) and then the DELETE. `SET LOCAL` auto-scopes to the transaction, so the
sanctioned purge is the only path that can delete. The anchor-hash logging stays.

## 4. Migrations & dependencies

- New: `emcip-audit-service/src/main/resources/db/changelog/004-audit-delete-prevention.xml`, wired
  into `db.changelog-master.xml`.
- New dependency: `io.projectreactor.kafka:reactor-kafka` in `emcip-audit-service/pom.xml`.
- No schema/column changes. Cron offsets on existing jobs already satisfy Rule 6.

## 5. Testing (Testcontainers — audit-service has a Kafka + Postgres harness)

1. **No-fork under concurrency** — publish N events across multiple topics concurrently; assert the
   chain is linear: every non-genesis `prev_hash` equals exactly one predecessor's `integrity_hash`,
   no two rows share a `prev_hash`, no gaps. (The regression that sank batch 1.2.)
2. **No silent loss** — force a save failure (e.g. transient DB error, then a permanent one); assert
   the record lands in `<topic>.dlq` and the offset advances; nothing is dropped.
3. **Tamper — content** — mutate a row's content (via the purge flag / superuser, bypassing the
   UPDATE trigger in-test); `verifyChain` reports a content-hash mismatch at that row.
4. **Tamper — linkage** — delete a middle row via the sanctioned path, then `verifyChain` reports
   broken linkage.
5. **DELETE trigger** — a direct `DELETE` (no purge flag) raises; retention (with the flag) succeeds
   and still purges expired rows.
6. **Tenant fail-closed** — a record with a missing/invalid tenant header is skipped (offset
   committed), consistent with current behaviour.

## 6. Documentation to update (per `documentation-checklist`)

- `documentation/diagrams/dataflow-audit-trail.puml` — chain activation + DLQ path.
- `documentation/diagrams/sequence-error-handling.puml` — audit consumer now participates in the
  retry/DLQ flow.
- `documentation/architecture-guide.adoc` — audit-integrity note (chain, advisory lock, DELETE
  trigger + purge flag); §5.3/5.4 if consumer-group/topic wording changes.
- `documentation/operations-guide.adoc` — operator note on the chain, the `emcip.audit_purge` flag,
  and reading `AuditChainVerificationJob` alerts.
- `docs/superpowers/BACKLOG.md` — flip RT2-002 / RT2-016 / B1 to done; record any follow-ups.
- `documentation/ROADMAP.md` — P2.1 delivery note (branch/PR + date + any scope corrections).

## 7. Risks & mitigations

- **New `reactor-kafka` dependency** — conscious call; standard Reactor library, isolated to
  audit-service. Mitigation: covered by the Testcontainers suite before merge.
- **Advisory lock contention / hangs** — lock is transaction-scoped and auto-released; a stuck
  transaction can't leak it beyond its own lifetime. Serial appends are the intended semantics.
- **Offset-commit ordering** — `concatMap` + commit-after-persist (or commit-after-DLQ) guarantees an
  offset never advances past an unpersisted, non-DLQ'd record.
- **Parallel-batch blindness (P1 lesson)** — run a combined audit-service integration build before
  merge; do not split this into sub-PRs.

## 8. Out of scope

Reactive Kafka for other services; retroactive hashing of pre-activation rows; audit-service replica
tuning (P4). The `v1:`-style forward-compat concerns of the secrets work do not apply here.
