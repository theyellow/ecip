# SC9: Docker Network Segmentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single `ecip-network` with three purpose-scoped networks (`data-tier`, `app-tier`, `monitoring-tier`) so data services are unreachable from monitoring tools and vice versa.

**Architecture:** Three bridge networks. Data services (postgres, kafka, zookeeper) are isolated on `data-tier`. App services span `app-tier` + `data-tier`. Monitoring tools (prometheus, tempo) also join `app-tier` so they can scrape/receive traces; internal monitoring communication uses `monitoring-tier`. Grafana, loki, promtail stay on `monitoring-tier` only.

**Tech Stack:** Docker Compose 3.8

---

## Context

### Why this matters

The current single `ecip-network` means every container can reach every other container — Grafana can reach postgres, promtail can reach Kafka. Network segmentation closes this: a compromised monitoring tool cannot lateral-move to the database.

### Network assignment

| Network | Members |
|---------|---------|
| `data-tier` | zookeeper, kafka, postgres, kafka-ui, pgadmin |
| `app-tier` | conversation-context, intent-classifier, policy-engine, llm-orchestrator, moderation-service, audit-service, admin-api, tdlib-adapter, admin-ui, **prometheus**, **tempo** |
| `monitoring-tier` | loki, promtail, prometheus, grafana, tempo |

App services join both `app-tier` and `data-tier` (they need postgres/kafka).
`prometheus` and `tempo` join both `app-tier` (to reach services) and `monitoring-tier` (internal comms).

### Test command

```bash
docker compose config > /dev/null && echo "Config valid"
```

---

## File Structure

**Modify:**

| File | Change |
|------|--------|
| `docker-compose.yml` | Add 3 networks, reassign service `networks:` stanzas, remove `ecip-network` |

---

## Task 1: Replace single network with three-tier segmentation

**File:** `docker-compose.yml`

- [ ] **Step 1: Verify current state**

```bash
grep -c "ecip-network" /home/ben/Development/ecip/docker-compose.yml
```

Expected: 16 (15 service references + 1 definition).

- [ ] **Step 2: Update the `networks:` block at the bottom of `docker-compose.yml`**

Read the file first. Find the `networks:` section at the very end (currently lines ~404-406):

```yaml
networks:
  ecip-network:
```

Replace it with:

```yaml
networks:
  data-tier:
    driver: bridge
  app-tier:
    driver: bridge
  monitoring-tier:
    driver: bridge
```

- [ ] **Step 3: Update data-tier services (data-tier only)**

For each of the following services, replace `networks:\n      - ecip-network` with `networks:\n      - data-tier`:

- `zookeeper`
- `kafka`
- `postgres`
- `kafka-ui`
- `pgadmin`

Example (for zookeeper):
```yaml
    networks:
      - data-tier
```

- [ ] **Step 4: Update app services (app-tier + data-tier)**

For each of the following services, replace `networks:\n      - ecip-network` with the two-network stanza:

- `conversation-context`
- `intent-classifier`
- `policy-engine`
- `llm-orchestrator`
- `moderation-service`
- `audit-service`
- `admin-api`
- `tdlib-adapter`

```yaml
    networks:
      - app-tier
      - data-tier
```

- [ ] **Step 5: Update admin-ui (app-tier only)**

`admin-ui` only communicates with `admin-api` (also on `app-tier`) and doesn't need database access:

```yaml
    networks:
      - app-tier
```

- [ ] **Step 6: Update loki and promtail (monitoring-tier only)**

```yaml
    networks:
      - monitoring-tier
```

- [ ] **Step 7: Update grafana (monitoring-tier only)**

Grafana queries prometheus, loki, and tempo — all of which are on `monitoring-tier`:

```yaml
    networks:
      - monitoring-tier
```

- [ ] **Step 8: Update prometheus (app-tier + monitoring-tier)**

Prometheus scrapes metrics from app services (needs `app-tier`) and participates in monitoring stack (needs `monitoring-tier`):

```yaml
    networks:
      - app-tier
      - monitoring-tier
```

- [ ] **Step 9: Update tempo (app-tier + monitoring-tier)**

Tempo receives traces pushed from app services (needs `app-tier`) and is queried by Grafana (needs `monitoring-tier`):

```yaml
    networks:
      - app-tier
      - monitoring-tier
```

- [ ] **Step 10: Verify no references to `ecip-network` remain**

```bash
grep "ecip-network" /home/ben/Development/ecip/docker-compose.yml
```

Expected: no output (zero matches).

- [ ] **Step 11: Validate docker-compose syntax**

```bash
cd /home/ben/Development/ecip && docker compose config > /dev/null && echo "Config valid"
```

Expected: `Config valid`

- [ ] **Step 12: Confirm correct network count**

```bash
cd /home/ben/Development/ecip && docker compose config | grep -E "^networks:" -A 20 | head -25
```

Expected: `data-tier`, `app-tier`, `monitoring-tier` appear; `ecip-network` does not.

- [ ] **Step 13: Commit**

```bash
cd /home/ben/Development/ecip && git add docker-compose.yml
git commit -m "chore(infra): segment docker-compose into data-tier/app-tier/monitoring-tier networks"
```

---

## Self-Review

### Spec coverage

| Requirement | Step |
|-------------|------|
| Three separate networks defined | Step 2 ✅ |
| Data services isolated on `data-tier` | Step 3 ✅ |
| App services on `app-tier` + `data-tier` | Step 4 ✅ |
| `admin-ui` on `app-tier` only | Step 5 ✅ |
| Monitoring stack on `monitoring-tier` | Steps 6–7 ✅ |
| `prometheus` + `tempo` bridge app and monitoring | Steps 8–9 ✅ |
| `ecip-network` fully removed | Step 10 ✅ |
| Config validates | Step 11 ✅ |

### What this achieves

- Grafana, loki, promtail **cannot** reach postgres or kafka (different network, no route)
- postgres and kafka **cannot** reach monitoring tools
- App services can still reach postgres and kafka (both on `data-tier`)
- Prometheus can still scrape app services (both on `app-tier`)
- Grafana can still query prometheus, loki, tempo (all on `monitoring-tier`)
- App services can still push traces to tempo (both on `app-tier`)

### Out of scope

- Changing exposed ports — all port bindings remain unchanged
- Adding firewall rules — this is Docker Compose only, not Kubernetes
