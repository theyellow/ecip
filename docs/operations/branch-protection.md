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
PR forever. Never require `build` or `code-quality` directly.

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
gh api --method PUT repos/theyellow/ecip/rulesets/15118295 -f enforcement=evaluate
# ... merge, then IMMEDIATELY:
gh api --method PUT repos/theyellow/ecip/rulesets/15118295 -f enforcement=active
```

---

## Verifying the gate still works

A gate that has not been seen to fail is not a gate. After any change to `ci-gate`,
`maven.yml`'s triggers, or the ruleset, re-run the P3.5a proofs: a PR with a failing
test must show `mergeStateStatus: "BLOCKED"`, and a docs-only PR must show
`"CLEAN"` with `build` skipped.
