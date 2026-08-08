# Branch protection — `main`

`main` is protected by repository ruleset **`Branchruleset`** (id `15118295`).
The payload is checked in at `.github/rulesets/main.json`, which is the source of
truth; the live ruleset must match it.

---

## What is enforced

| Rule | Effect |
|---|---|
| `deletion` | `main` cannot be deleted |
| `non_fast_forward` | no force-pushes to `main` |
| `pull_request` (0 approvals) | all changes arrive via PR |
| `required_status_checks` | `ci-gate` and `trufflehog` must pass; non-strict |

**`ci-gate`** is an aggregator job in `.github/workflows/maven.yml`. It runs on every
PR without exception and reports one conclusion for the whole Maven workflow.
Upstream jobs that are *skipped* (docs-only PRs) pass it; *failed* or *cancelled*
jobs fail it. It exists because a workflow prevented from triggering by a `paths`
filter reports no status at all, and a required check that never reports blocks the
PR forever. Never require `build` or `code-quality` directly. `ci-gate`'s `needs:`
list **is** the gate's real definition — a job added to `maven.yml` but not added to
that list gates nothing, however it fails or hangs.

**Non-strict** means a PR need not be rebased onto current `main` before merging. Two
PRs green in isolation but broken together are caught by the push-to-`main` run, not
by the gate. Revisit if PR concurrency rises.

`Analyze Java` (CodeQL) is deliberately **not** required: it is slow and alert-driven,
and its findings are triaged in the Security tab rather than acting as a merge signal.

---

## Applying a change

Edit `.github/rulesets/main.json`, then:

```bash
gh api --method PUT repos/theyellow/ecip/rulesets/15118295 --input .github/rulesets/main.json
gh api repos/theyellow/ecip/rulesets/15118295 | jq '[.rules[].type]'
```

Always verify by reading the live ruleset back — the PUT succeeding is not proof the
rule landed as intended.

---

## When CI is down

A GitHub Actions outage means required checks never report and nothing can merge.
This is intended: PR #229 merged during the 2026-08-06 outage with zero checks, which
is what prompted P3.5a. Two options, in order of preference:

1. **Wait.** Almost always correct.
2. **Repo admins** may merge via the PR bypass (`bypass_mode: "pull_request"`).
   Bypasses are recorded in the ruleset audit log. Use deliberately, not routinely.

Last resort, if the bypass is unavailable:

```bash
jq '.enforcement="disabled"' .github/rulesets/main.json | gh api --method PUT repos/theyellow/ecip/rulesets/15118295 --input -
# ... merge, then restore the unmodified payload:
gh api --method PUT repos/theyellow/ecip/rulesets/15118295 --input .github/rulesets/main.json
```

Use `disabled`, not `evaluate` — `evaluate` is a GitHub Enterprise-only enforcement
state and will almost certainly 422 on this user-owned personal repo. Also send the
**full payload** with `enforcement` changed, not a bare `-f enforcement=...`: whether
GitHub preserves or clears the omitted `rules`/`bypass_actors` on a partial write is
undocumented, and if cleared, a bare `-f` write would silently strip *all* protection
from `main` — deletion, force-push, PR-only — not just the status-check requirement,
and the restore step would not bring it back.

**Verified 2026-08-08** against ruleset `15118295`: the full-payload
`enforcement=disabled` write was **ACCEPTED** on this user-owned repo (confirming the
suspicion that drove this rewrite — the old bare `-f enforcement=evaluate` form is
Enterprise-only and would have 422'd here). Read-back during the disabled window
showed all four rules (`deletion`, `non_fast_forward`, `pull_request`,
`required_status_checks`) and the single bypass actor still present — a full-payload
write does not strip rules, which was the specific risk with the previous bare `-f`
form. The restore write (`--input .github/rulesets/main.json`) returned the ruleset
byte-identical (via `diff` on `jq -S` output) to both the pre-exercise snapshot and
the checked-in payload. `main` was unprotected for roughly 20 seconds. This sequence
has been run for real and the rules survive the disabled window — it can be followed
under pressure without improvising.

---

## Verifying the gate still works

A gate that has not been seen to fail is not a gate. After any change to `ci-gate`,
`maven.yml`'s triggers, or the ruleset, re-run the P3.5a proofs: a PR with a failing
test must show `mergeStateStatus: "BLOCKED"`, and a docs-only PR must show
`"CLEAN"` with `build` skipped.

---

## Residual risks (known, accepted, not fixed here)

- **The gate does not protect its own definition.** For `pull_request` events GitHub
  runs the workflow from the PR's merge ref, i.e. a PR's edits to `maven.yml` govern
  the checks that PR itself is judged against. This repo is public with forking
  enabled and `required_approving_review_count: 0`, so a PR that edits `ci-gate` to
  `exit 0` (or removes a job from its `needs:`) self-approves its own gate. A
  CODEOWNERS-based mitigation for `.github/workflows/**` is a separate decision, not
  yet made.
- **`pull_request` retarget with no new commits deadlocks.** `maven.yml`'s trigger
  uses the default `types` (`opened`, `synchronize`, `reopened`), which excludes
  `edited`. A PR opened against another branch and later retargeted to `main` without
  a new commit fires no workflow run, so `ci-gate` never reports and the PR sits at
  "Expected — waiting for status to be reported" permanently. Recoverable with an
  empty commit or the admin bypass; the trigger `types` are deliberately left
  unchanged.
- **The in-workflow filter excludes more Markdown than the old trigger filter did.**
  The `changes` job's `code` filter negates `!**/*.md` (any Markdown, any depth); the
  `push` trigger's `paths-ignore` above it only negates top-level `*.md`. Currently
  harmless — no Markdown file is a build or runtime input — but a future `.md` placed
  under, say, `src/main/resources` would skip CI for a change that ought to run it.
