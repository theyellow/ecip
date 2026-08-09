# EMCIP Rate Limiting Runbook

How per-caller rate limiting works in `emcip-admin-api`, what to watch, and how
to re-verify the one setting that cannot be proven by tests.

Delivered by P3.6. Related: `docs/operations/rollout.md`,
`documentation/operations-guide.adoc`.

---

## What is limited

`RateLimitWebFilter` runs in the admin-api security chain, immediately after
authentication, and applies a limit to **every** request it sees. Requests are
sorted into one of three groups by `RateLimitGroups.resolve(method, path)` —
first match wins.

| Group | Matches | Keyed by | Limit |
|-------|---------|----------|-------|
| `auth` | `/api/auth/**`, `/auth/**` | **Client IP** | 10 / 60s |
| `llm-trigger` | `POST /api/flags/*/analyse`, `POST /api/flags/*/chat`, `POST /api/simulate/message` | JWT subject | 20 / 60s |
| `admin-crud` | everything else under `/api/**` (catch-all) | JWT subject | 100 / 60s |

Exempt (never limited): `/actuator/**` and `/api/internal/**`.

The numbers live in `emcip-admin-api/src/main/resources/application.yml` under
`resilience4j.ratelimiter.instances`. Only the *config* is read from there — the
registry instance is a template, and the filter creates one limiter **per key**
from it.

### Why auth is keyed by IP and the rest by user

Auth endpoints are unauthenticated: at the point the limiter runs there is no
principal, so the only identity available is the network origin. Everywhere else
there is a JWT subject, which is a far better key — it survives NAT, and one
user behind a shared egress IP cannot exhaust everyone else's quota.

On an authenticated group with no principal (an unauthenticated request that
still reaches the filter), the key falls back to the client IP.

### The catch-all is deliberate

`admin-crud` is a fallback, not an allowlist. Any `/api/**` endpoint added later
is rate-limited by default without anyone remembering to annotate it. This is
what closed the `POST /api/flags/{id}/reply` gap — that endpoint had no limiter
at all under the previous per-controller scheme.

`resolve` fails **closed** in the same spirit: a path nobody wrote a rule for,
or one shaped oddly enough that canonicalization is ambiguous, lands in
`admin-crud` rather than escaping limits. Only a literal match against the
exempt prefixes is exempt.

---

## `emcip.security.trusted-proxy-hops`

**This is the setting that is silently wrong if the topology changes, and no
test can catch it.**

`ClientIp` reads `X-Forwarded-For` and takes the Nth entry **counting from the
right**, where N is `trusted-proxy-hops`. Counting from the right is the whole
point: the leftmost entry is whatever the client sent, so keying a limiter on it
lets an attacker mint a fresh bucket per request simply by varying the header.
Entries an attacker prepends land to the *left* of the trusted position and are
ignored, however many they add.

The assumed chain is:

```
client -> nginx ingress -> admin-ui BFF -> admin-api
```

The BFF appends its own hop (it rewrites `X-Forwarded-For` rather than passing
the client's copy through), which makes hop **2** the real client under either
nginx `use-forwarded-headers` mode. Current value: `trusted-proxy-hops: 2`.

> ⚠️ **Status: NOT YET VERIFIED against a live request.** The value is derived
> from the topology above, not observed. Run the procedure below and record the
> result here before treating it as proven.

If the header carries fewer entries than the hop count, `ClientIp` does **not**
guess. It falls back to the socket address and increments
`emcip.ratelimit.untrusted_ip`, so a topology change surfaces in metrics instead
of silently selecting an attacker-controlled value.

**Changing the ingress, inserting a proxy or CDN, or altering how the BFF
forwards headers invalidates this number.** Re-run the verification below when
any of those change.

### Verifying the hop count

1. From **outside** the cluster, send a login attempt through the real ingress
   with a deliberately spoofed header:

   ```bash
   curl -si -X POST https://<ingress-host>/api/auth/token \
     -H 'Content-Type: application/json' \
     -H 'X-Forwarded-For: 9.9.9.9' \
     -d '{"username":"<user>","password":"<wrong-password>"}' | head -20
   ```

2. Read what admin-api actually resolved. The login failure is audited with the
   client IP (P2.8):

   ```bash
   microk8s.kubectl -n emcip logs deploy/emcip-admin-api --tail=100 \
     | grep -i "rate limit\|LOGIN_FAILURE"
   ```

3. Judge the result:

   | Observed IP | Meaning | Action |
   |---|---|---|
   | Your real public IP | Correct | Hop count confirmed — record it below |
   | `9.9.9.9` | **Spoof succeeded** | Hop count too high. Count the real chain from the header, correct the property, repeat |
   | A pod/service IP (`10.x`) | Counting too far right | Decrement the hop count and repeat |

4. Confirm the fallback metric is quiet:

   ```bash
   microk8s.kubectl -n emcip exec deploy/emcip-admin-api -- \
     curl -s localhost:8080/actuator/metrics/emcip.ratelimit.untrusted_ip
   ```

   A non-zero and rising count means resolution is falling back to the socket —
   the IP keying has stopped being meaningful.

5. Record the outcome in the table below and update the comment in
   `application.yml`.

### Verification log

| Date | Value verified | Observed | Verified by |
|------|----------------|----------|-------------|
| _pending_ | `2` (assumed) | — | — |

---

## Metrics

| Metric | Tags | Meaning |
|--------|------|---------|
| `emcip.ratelimit.rejected` | `group` | A request was refused with 429. Sustained non-zero on `auth` is the brute-force signal |
| `emcip.ratelimit.untrusted_ip` | `reason` | `ClientIp` could not trust the header and fell back to the socket address |

`untrusted_ip` is the one to alert on. While it is rising, per-IP limits are
effectively per-socket: behind a proxy that means *every* client shares one
bucket (over-limiting), and the `auth` group's protection is no longer per
attacker. Investigate the proxy chain before assuming the limits still hold.

Rejections are also logged at WARN. The key is included **only** for the `auth`
group — brute-force forensics need the IP, and P2.8 already records client IPs
for login events. For the other groups the key is a username, so it is omitted.

---

## What a rejected caller sees

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded"}
```

The filter writes this response itself rather than throwing. `GlobalExceptionHandler`
is a `@RestControllerAdvice` and never sees exceptions thrown from a `WebFilter`,
so throwing here would surface to the client as a **500** — the same defect
AUTHZ-500 (P3.3) fixed for authorization denials. The body is byte-identical to
that handler's `RequestNotPermitted` mapping so clients see one format
regardless of which layer rejected them.

---

## Memory and eviction

Each group holds a Caffeine cache of limiters keyed by IP or username:

- `maximumSize` 10 000 keys per group,
- `expireAfterAccess` 2 minutes.

The TTL is longer than the 60s refresh period, so a limiter is not recreated
mid-window; a key idle for longer than the TTL is evicted and starts full on its
next request, which is correct — it made no requests during the window.

An unauthenticated flood from many source IPs is therefore bounded at 10 000
live limiters for the `auth` group rather than growing without limit.

---

## Caveat: single replica

Limits are **in-process**. With `replicas: 1` (current deployment) the counts
are exact. Scaling admin-api horizontally multiplies every effective limit by
the replica count, because each pod keeps its own counters and nothing is shared.

If admin-api is ever scaled out, the limits above stop meaning what this document
says and need a shared backing store. Tracked as **P1-M2**.

---

## Tuning the limits

Edit `resilience4j.ratelimiter.instances.<group>` in `application.yml` and roll
out admin-api (`docs/operations/rollout.md`). There is no runtime override — a
change requires a restart.

Before raising a limit, check `emcip.ratelimit.rejected{group}` to confirm the
rejections are legitimate traffic rather than a single misbehaving caller; the
whole point of per-key limiting is that one caller's excess no longer justifies
raising the ceiling for everyone.
