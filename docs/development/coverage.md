# Test Coverage Gate

JaCoCo enforces a **per-module line-coverage floor** during `mvn verify`. The build
fails if a module drops below its floor.

## Floors

| Module | Measured | Floor |
|---|---|---|
| emcip-admin-api | 55.4% | 0.53 |
| emcip-admin-ui | 76.5% | 0.74 |
| emcip-audit-service | 73.5% | 0.71 |
| emcip-conversation-context | 74.8% | 0.72 |
| emcip-core | 80.8% | 0.78 |
| emcip-intent-classifier | 71.5% | 0.69 |
| emcip-knowledge-engine | 75.0% | 0.73 |
| emcip-llm-orchestrator | 54.6% | 0.52 |
| emcip-moderation-service | 85.4% | 0.83 |
| emcip-policy-engine | 72.4% | 0.70 |
| emcip-tdlib-adapter | 44.7% | 0.42 |

Root default is `0.00`: a newly added module inherits a floor it cannot fail, so
setting its real floor is an explicit step in that module's own PR.

## Rules

- A floor may be **raised** freely.
- A floor may only be **lowered** with a written reason in the commit body.
- Floors are set at `measured − 2` percentage points, rounded down.

## Re-measuring

```bash
mvn -B clean verify
./scripts/coverage-floors.sh
```

Requires Docker — the merged number includes Testcontainers-backed integration tests.

## What counts

Coverage is measured on **merged** unit (surefire) and integration (failsafe)
execution data. Excluded from the denominator: `**/dto/**`, `**/entity/**`,
`**/*Application.class`, `**/config/*Properties.class`. Lombok-generated members are
excluded via `lombok.config` (`addLombokGeneratedAnnotation = true`).

Config and security classes are deliberately **not** excluded.

Only 5 modules contain `*IT`/`*IntegrationTest` classes — emcip-audit-service,
emcip-conversation-context, emcip-knowledge-engine, emcip-moderation-service,
emcip-policy-engine — so only those modules produce a `jacoco-it.exec` file. A module
without integration tests legitimately has no IT exec file; that is not a bug.

## Gotchas

Both of these fail **silently** — the build stays green and coverage is wrong.

**1. `@{}` not `${}`.** Surefire and Failsafe must reference the agent argLine as
`@{surefireArgLine}` / `@{failsafeArgLine}`. Maven interpolates `${...}` in plugin
configuration at model-build time, so the empty property declared in `<properties>`
is baked in before `prepare-agent` runs and the agent never attaches. No exec data is
written, and JaCoCo's `check` goal *skips* rather than fails when data is missing — so
the gate reports success while measuring nothing.

Detect it with `grep -c "Skipping JaCoCo" <build log>` — expected `0`.

This actually happened during implementation: a build reported `BUILD SUCCESS` across
13 modules with 193 test classes and produced zero `.exec` files.

**2. Distinct property names.** Surefire and Failsafe both read the plain `argLine`
property by default. The two `prepare-agent` executions therefore use
`surefireArgLine` and `failsafeArgLine`; giving them the same name silently discards
one set of execution data.

## Verifying the gate is real

A coverage gate that cannot be shown to fail should not be trusted — this repo has
already produced one false-green build (see Gotcha 1 above). Prove the gate actually
enforces the floor:

```bash
mvn -B verify -pl emcip-core -Djacoco.minimum-coverage=0.99
```

Expected: the build **FAILS** with

```
Rule violated for bundle emcip-core: lines covered ratio is 0.80, but expected minimum is 0.99
```

If this command instead reports `BUILD SUCCESS`, the gate is not wired correctly —
check for the two gotchas above before trusting any coverage number this pipeline
reports.
