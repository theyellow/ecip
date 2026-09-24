# Incident: admin-api CrashLoopBackOff after P3.7 (2026-09-13 → 2026-09-20)

**Impact:** admin-api could not start for ~7 days (8,700+ restarts). The Admin UI was
non-functional for the whole period: no credentials, tenants, flags, users or rules.
**Trigger:** first cluster deploy containing P3.7 — the secrets startup self-check (PR #245,
merged 2026-08-17).
**Fixed by:** PR #246 (2026-09-20). **Regression guard:** `AdminApiBootIT`, PR #251.

> This was originally filed as `ADR-009`. It records an incident and its fix, not an
> architecture decision, so it moved here; the ADR-009 number stays reserved for the
> multi-tenancy ADR planned under ROADMAP 3.14.

## What happened

```
Parameter 0 of method secretColumnScanner in io.emcip.common.crypto.SecretsSelfCheckConfig
required a bean of type 'javax.sql.DataSource' that could not be found.
APPLICATION FAILED TO START
```

P3.7's `SecretsSelfCheckConfig` (emcip-core) scans secret columns over plain JDBC and needs a
`javax.sql.DataSource` bean. knowledge-engine and llm-orchestrator are JPA services and have one.
admin-api is R2DBC and never did.

## Root cause

Spring Boot 4's `DataSourceAutoConfiguration` is annotated
`@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")`: in any service with an R2DBC
`ConnectionFactory`, **no JDBC `DataSource` is auto-configured, whatever `spring.datasource.*`
says**. admin-api's only JDBC connection was a `DriverManagerDataSource` built privately inside
`LiquibaseConfig.liquibase()` and never exposed as a bean.

## Why nothing caught it

- admin-api had **no `@SpringBootTest` at all** — this was already filed as SELFCHECK-F2 during
  P3.7 ("a wrong table/column name would fail at runtime, not at build time"), rated LOW/P4. The
  real exposure was wider than the one it described: not a wrong identifier, but a context that
  could not start.
- P3.7's live-cluster verification (spec §7, SELFCHECK-F3) was deferred, so the first boot on a
  cluster was also the first time anyone could have seen it.
- The unit tests all construct the scanner by hand with a mocked `DataSource`, so none of them
  exercises bean wiring.

## Fix (PR #246)

`LiquibaseConfig` exposes the `DriverManagerDataSource` as a `@Bean`, used by both Liquibase and
the self-check scanner. It is not used for application data access, which stays on R2DBC. Helm
also sets `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_DRIVER_CLASS_NAME` for admin-api.

Side effects to be aware of: now that a `DataSource` bean exists, Boot's JDBC auto-configuration
also contributes a JDBC transaction manager and a `db` health contributor. admin-api uses no
`@Transactional`, so there is no transaction-manager ambiguity today — but adding one would need
an explicit qualifier.

Alternatives rejected at the time: moving the scanner to R2DBC (large refactor for a once-per-hour
scan), `@ConditionalOnBean(DataSource.class)` on the self-check (silently disables it in exactly
the service that stores Telegram credentials), dropping the self-check from admin-api, or moving
admin-api to JDBC.

## Evidence after the fix

Boot log on the cluster (2026-09-20):

```
Started AdminApiApplication in 58.306 seconds
SECRET SELF-CHECK  mode=warn
  telegram_accounts.api_hash            1 encrypted,    0 plaintext  [OK]
  telegram_accounts.session_string      0 encrypted,    0 plaintext  [UNVERIFIED]
```

This is the first live reading of admin-api's self-check, and it covers part of SELFCHECK-F3:
`session_string` reports `UNVERIFIED` as that item predicted. The gauges, the other two
services and the hourly re-scan are still unread.

## Follow-ups

| Item | Status |
|------|--------|
| `AdminApiBootIT`: boots the real context on Testcontainers PostgreSQL and asserts both columns were scanned; forced red with #246 reverted | ✅ PR #251 (closes SELFCHECK-F2) |
| SELFCHECK-F3: read the live gauges in all three services | ⏳ unblocked, cluster available again |
| SELFCHECK-F1: admin-api scans as the Liquibase role, not the runtime R2DBC role | ⏳ P4 |

## Lesson

A shared `emcip-core` component that pulls in a bean dependency needs a context-load test in
**every** service that imports it, not just the one it was developed in. "The wiring is trivial"
is exactly the case where only a real context proves anything.
