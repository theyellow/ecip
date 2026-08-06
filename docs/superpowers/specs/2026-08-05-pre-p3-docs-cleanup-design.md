# Pre-P3 documentation cleanup — design

- **Date**: 2026-08-05
- **Branch**: `docs/pre-p3-cleanup` (stacked on `feat/p2.8-failed-login-audit` / PR #224 — merge #224 first, or rebase this onto main after)
- **Files**: `documentation/ROADMAP.md`, `docs/superpowers/BACKLOG.md`, phase-status memory. **Not** `.claude/CLAUDE.md` (config-protected; unaffected by the renumber anyway).
- **Type**: documentation reorganization only. No code.

## Goal

P2 is complete. Make the planning docs show *active/next* work at the top and stop
implying there are two pre-release phases. Concretely: archive the finished P0–P2
detail to the bottom, collapse the phase model to one pre-release phase (P3) + clearly
post-release phases, and pull the open follow-ups into the ROADMAP so it is the full
forward sequence.

## Phase model change (the core edit)

**Before:** P0✅ P1✅ P2✅ · P3 pre-1.0.0 · **P4 "1.0.0 polish + cheap wins"** · P5 post-1.0.0 features · P6 long horizon.
The "P4 = 1.0.0 polish" title reads as pre-release, but P4 never gated 1.0.0.

**After:** one pre-release phase, everything else explicitly post-release.

| New | Title | Was | Notes |
|-----|-------|-----|-------|
| **P3** | Pre-1.0.0 release-readiness → **ships 1.0.0** | P3 | Two tiers: *release gate (mandatory)* + *recommended before ship (non-gating)*. |
| **P4** | Post-1.0.0 — features + deferred polish | **P5** (+ old P4 leftovers) | Renumbered. Absorbs old-P4 non-gating cheap wins + the remediation follow-up sweep. |
| **P5** | Long horizon | **P6** | Renumbered. |
| ~~P4~~ | ~~1.0.0 polish + cheap wins~~ | — | **Dissolved**; items redistributed (below). |

### Old-P4 item redistribution
- **→ P3 (recommended, non-gating):** `RT-F1` per-user/IP rate limiting (security hardening worth having at ship).
- **→ new P4 (post-1.0.0):** `RT-F5` (BackfillService PARTIAL status), `#45` (language detection), `#46` (Unicode umlaut regex), toast migration, KE-enrichment follow-ons.

### Follow-ups folded into the ROADMAP (from BACKLOG §0b — §0b stays the status source of truth, ROADMAP gains sequence rows)
- **→ P3 (recommended tier):** `INF-CI-IT` (Java `*IT` failsafe wiring — sits with `3.9` Gatling / `3.12` INF-CI-FE; also un-gates P2.8's new `AdminAuditEventPersistenceIT`), `P1-M1` (`@PreAuthorize` live-filter e2e test), `P1-M3` (float base-image pinning), `P2.0-F1` (strict-startup self-check).
- **→ new P4, one grouped "Remediation follow-up sweep" row:** the LOW nits — `SSRF-F1..F4`, `P2.8-F1/F2/F3`, `RT2-007-F1/F2`, `P1-M2` (per-replica JWT revocation), `P1-M4`, `P2.0-M1/M2`, `P2.1-F1`, `KE-TRUST` — each cross-referenced to its §0b row (do not duplicate the detail; §0b owns it).

## ROADMAP target section order

1. Title + "How to read this"
2. **Phase skeleton** table (updated: P4/P5/P6 → P4/P5; P2 gate `✅ done (2.0–2.8)`; P3 `→ 1.0.0`)
3. One-line **1.0.0 gate** statement (what must be done = end of P3)
4. **## P3 — Pre-1.0.0 release-readiness (ACTIVE)** — gate tier + recommended tier + the new **clean-boot prerequisites** note
5. **## P4 — Post-1.0.0** (renumbered P5 + leftovers + follow-up sweep)
6. **## P5 — Long horizon** (renumbered P6)
7. **## Cross-references**
8. **## Completed phases (P0–P2) — archive** ← P0/P1/P2 sections relocated here **verbatim** (delivered-notes + lessons intact: P1.2 deferral rationale, P1.4 combined-review lesson, all "P2.x delivered" notes).

## Clean-boot prerequisites note (new, under P3.1–3.4)

> **Known state — local cluster is hand-wired.** The dev k8s cluster is not a clean
> install: Liquibase was bypassed, some tables (conversation-context) were created by
> hand, postgres was patched in place. A from-zero `helm install` currently would not
> come up. Boot-from-zero needs: **consolidated Liquibase** (3.1) · the **`SecretCipher`
> encryption key + provider API-key secrets** provisioned as k8s Secrets · **tenant
> seed** (3.3) · **telegram test-account seed** (3.4). Validate together with the user
> (3.2). (Mirrors the `project-phase-status` memory "Cluster state — still hand-wired".)

## BACKLOG target structure

- Keep §0b (open follow-ups) where it is — near the top, actionable.
- Move the now-all-✅ §0a rows into **§5 Completed** under a labeled subsection
  "P1–P2 security remediation (2026-07-18 reviews)", preserving each row's delivered-note.
  Leave §0a as a one-line stub pointing to §5 (so the phase-ordered history isn't lost,
  just relocated).
- Update the top "Last updated" line to 2026-08-05 / P2.8.

## Ripple checklist (exact)

- ROADMAP line ~18 `P4–P6 are post-release` → `P4–P5 are post-release`.
- ROADMAP skeleton table rows P4/P5/P6 → P4/P5 (drop the dissolved-P4 row; retitle).
- ROADMAP line ~145 (P2 delivered note) `deferred to P6` → `deferred to P5`.
- BACKLOG §0b `P2.0-F1` text `P6 secrets ADR` → `P5 secrets ADR`. (§0b `P4` labels stay:
  new-P4 = post-1.0.0, so "deferred to P4" is now consistent. §0b header "deferred to
  P3/P4" stays valid.)
- Memory `project_phase_status.md`: `phased P0–P6` → `P0–P5`; the phase-list line
  `P4 polish · P5 post-1.0.0 · P6 long horizon` → `P4 post-1.0.0 · P5 long horizon`.

## Out of scope

- `.claude/CLAUDE.md` — its "Current Phase Status" section (Epics 3.1–3.3, SC1–SC9) is
  stale and unrelated to the P-phase model; fixing it needs separate explicit approval
  (config-protection rule). Flagged, not touched here.
- No content is deleted — only relocated/renumbered. Verification: `grep` finds no
  orphaned `P6`, and every relocated section heading still exists exactly once.

## Verification

- `grep -nE "\bP6\b" documentation/ROADMAP.md docs/superpowers/BACKLOG.md` → no hits.
- Every "P2.x delivered" note and the P1.2/P1.4 lesson blocks still present (in the
  archive section).
- BACKLOG §0a rows all appear under §5; §0b unchanged in content.
- Both files render (headings well-formed, tables intact).
