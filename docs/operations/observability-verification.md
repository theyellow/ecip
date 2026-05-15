# Observability Verification Runbook

After deploying or updating the EMCIP stack, follow these steps to verify the full
observability pipeline: metrics, logs, and traces.

---

## Prerequisites

- Grafana accessible at `http://localhost:14007` (docker-compose) or `http://emcip.local/grafana` (k8s)
- All EMCIP services running and healthy
- At least one message has been processed (trigger via Admin UI or send a test Kafka event)

---

## 1. Verify Prometheus scrape targets

1. Open Grafana → Explore → datasource: **Prometheus**
2. Run: `up{job=~"emcip-.*"}`
3. Expected: all 8 services show value `1`

If any show `0`: check `docker compose logs <service>` or `microk8s.kubectl logs -n emcip deploy/emcip-<service>`.

---

## 2. Verify traces arrive in Tempo

1. Open Grafana → Explore → datasource: **Tempo**
2. In the search form, set **Service Name** = `emcip-moderation-service`
3. Click **Run query**
4. Expected: trace results appear in the list

If empty: check `docker compose logs tempo` for OTLP ingestion errors. Verify
`OTEL_EXPORTER_OTLP_ENDPOINT` is set on the service container.

---

## 3. Verify trace span tree

1. Click any trace from step 2
2. Expand the span tree
3. Expected: at least one span visible with operation name and duration

---

## 4. Verify trace-log correlation (Loki → Tempo)

1. Open Grafana → Explore → datasource: **Loki**
2. Run: `{job="emcip"} | json | traceId != ""`
3. In any log line that shows a `traceId` field, click the **TraceID** link
4. Expected: browser jumps to Grafana Explore → Tempo, showing the matching trace

If the link is missing: the Loki `derivedFields` configuration is not applied.
Restart Grafana after updating `datasources.yml`.

---

## 5. Verify cross-service trace propagation

1. In Grafana → Explore → Tempo, search for a trace from `emcip-tdlib-adapter`
2. Look at the span tree — if a message was moderated, you should see child spans
   in `emcip-moderation-service` within the same trace
3. Expected: W3C `traceparent` propagated the trace ID across service boundaries via Kafka headers

---

## Quick Tempo API check (without Grafana)

```bash
# docker-compose
curl -s "http://localhost:14011/api/search?service.name=emcip-moderation-service" | jq .

# microk8s (port-forward first)
microk8s.kubectl port-forward -n emcip svc/emcip-tempo 14011:3200
curl -s "http://localhost:14011/api/search?service.name=emcip-moderation-service" | jq .
```
