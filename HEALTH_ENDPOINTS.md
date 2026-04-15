# Health Endpoints

This document describes the health check endpoints available for all EMCIP services.

## Overview

All services expose health endpoints via Spring Boot Actuator. These endpoints are used for:
- Docker container health checks
- Kubernetes liveness and readiness probes
- Monitoring and alerting systems
- Load balancer health checks

## Standard Health Endpoint

**URL:** `GET /actuator/health`

**Response Format:**
```json
{
  "status": "UP",
  "components": {
    "ping": {
      "status": "UP"
    },
    "db": {
      "status": "UP",
      "details": {
        "service": "conversation-context",
        "database": "postgresql",
        "status": "not-connected-yet"
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "service": "conversation-context",
        "broker": "kafka",
        "status": "not-connected-yet"
      }
    }
  }
}
```

## Service Health Endpoints

| Service | Port | Health URL |
|---------|------|------------|
| emcip-tdlib-adapter | 9080 | `http://localhost:9080/actuator/health` |
| emcip-conversation-context | 9081 | `http://localhost:9081/actuator/health` |
| emcip-intent-classifier | 9082 | `http://localhost:9082/actuator/health` |
| emcip-policy-engine | 9083 | `http://localhost:9083/actuator/health` |
| emcip-llm-orchestrator | 9084 | `http://localhost:9084/actuator/health` |
| emcip-moderation-service | 9085 | `http://localhost:9085/actuator/health` |
| emcip-audit-service | 9086 | `http://localhost:9086/actuator/health` |
| emcip-admin-api | 9087 | `http://localhost:9087/actuator/health` |

## Health Indicators

### Phase 1: Basic Health

All services currently expose:
- **ping**: Basic application responsiveness
- **db**: Database connectivity indicator (stub - Phase 2)
- **kafka**: Kafka connectivity indicator (stub - Phase 2)

### Phase 2: Enhanced Health

In Phase 2, the health indicators will be enhanced to:
- Actually verify database connectivity via PostgreSQL R2DBC
- Actually verify Kafka broker connectivity
- Add custom business logic health checks

## Docker Health Checks

All Dockerfiles include health checks:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:{port}/actuator/health || exit 1
```

## Monitoring Integration

### Prometheus (emcip-audit-service)

The audit service exposes Prometheus metrics at:
- `http://localhost:9086/actuator/prometheus`

### Manual Testing

Test a health endpoint:
```bash
curl http://localhost:9080/actuator/health
curl http://localhost:9081/actuator/health
```

## Status Codes

| Status | Meaning |
|--------|---------|
| `UP` | Service is healthy |
| `DOWN` | Service is unhealthy |
| `OUT_OF_SERVICE` | Service is intentionally taken out of service |
| `UNKNOWN` | Health status is unknown |

## Configuration

Health endpoints are configured in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
```

## Security Considerations

- Health endpoints are currently exposed without authentication (Phase 1)
- In later phases, consider restricting access to internal networks
- Do not expose sensitive information in health details
