# Performance Benchmarks — EMCIP

## SLOs

| Metric | Target |
|--------|--------|
| p95 intent classification | < 200ms |
| p95 policy evaluation | < 100ms |
| p99 end-to-end pipeline | < 2s |
| Kafka throughput | 500 msg/s sustained |

## Running Load Tests

Load tests require the full stack running locally:

```bash
# Start infrastructure
docker compose up -d postgres kafka zookeeper

# Run all simulations
cd gatling-tests && mvn gatling:test

# Review results
open gatling-tests/target/gatling/*/index.html
```

## Tuning Applied

### HikariCP (emcip-policy-engine)
- `maximum-pool-size: 20` — increased from default 10 to handle burst load
- `minimum-idle: 5` — keep warm connections ready
- `connection-timeout: 30000ms` — fail fast under extreme load

### Kafka Consumer (emcip-intent-classifier)
- `max-poll-records: 500` — process up to 500 records per poll cycle
- `fetch-min-size: 1` — fetch immediately when any record is available
- `fetch-max-wait: 500ms` — max wait before fetch returns empty

## Profiling with Java Flight Recorder

```bash
# Enable JFR on a running service
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
     -XX:+UnlockDiagnosticVMOptions \
     -jar emcip-policy-engine.jar

# Analyze with JDK Mission Control
jmc recording.jfr
```

## Baseline Results

Run after each significant change to track regressions. Store results in `documentation/developer/benchmarks/`.
