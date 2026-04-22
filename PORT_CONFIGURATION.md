# EMCIP Port Configuration

## Overview

To avoid conflicts with existing Kubernetes deployments and common development tools, EMCIP uses a dedicated port range for infrastructure services while keeping the application port range intact.

## Port Ranges

### Application Services (UNCHANGED)
| Service | Port | Purpose |
|---------|------|---------|
| emcip-tdlib-adapter | 9080 | Telegram integration |
| emcip-conversation-context | 9081 | Thread tracking |
| emcip-intent-classifier | 9082 | Intent detection |
| emcip-policy-engine | 9083 | Policy rules |
| emcip-llm-orchestrator | 9084 | LLM routing |
| emcip-moderation-service | 9085 | Content moderation |
| emcip-audit-service | 9086 | Logging/metrics |
| emcip-admin-api | 9087 | Admin endpoints |

### Infrastructure Services (NEW: 14000-14099 range)
| Service | Port | Purpose | Old Port |
|---------|------|---------|----------|
| Zookeeper | 14001 | Coordination service | 2181 |
| Kafka Internal | 14002 | Kafka broker (internal) | 9092 |
| Kafka External | 14003 | Kafka broker (external) | 29092 |
| Kafka UI | 14004 | Kafka management UI | 8080 |
| PostgreSQL | 14005 | Primary database | 5432 |
| pgAdmin | 14006 | PostgreSQL admin UI | 5050 |
| Grafana | 14007 | Observability dashboards (admin/admin) | 3000 |
| Loki | 14008 | Log aggregation backend | 3100 |
| Admin UI | 14009 | React admin SPA | 14009 |

## Environment Variables

Update your environment to use the new ports:

```bash
# Kafka
export KAFKA_BOOTSTRAP_SERVERS=localhost:14003
export KAFKA_INTERNAL_HOST=localhost:14002

# PostgreSQL
export POSTGRES_HOST=localhost
export POSTGRES_PORT=14005
export POSTGRES_USER=emcip
export POSTGRES_PASSWORD=emcip
export POSTGRES_DB=emcip

# Zookeeper (internal)
export ZOOKEEPER_HOST=localhost:14001

# Management UIs
export KAFKA_UI_URL=http://localhost:14004
export PGADMIN_URL=http://localhost:14006
```

## Docker Compose Access

When using Docker Compose, services communicate internally via service names:

```yaml
# Internal Docker network (unchanged)
Kafka connects to: zookeeper:14001
Services connect to: kafka:14002 (internal) or kafka:14003 (external)
Services connect to: postgres:14005
```

## Migration from Old Ports

If you have existing data or scripts using old ports:

1. Update all `application.yml` files with new port references
2. Update all documentation references
3. Update shell scripts and automation
4. Update IDE run configurations
5. Clear any cached connection strings

## Common Issues

### Port 1400x already in use
If a port in the 14000-14099 range is occupied:
- Check with: `lsof -i :14005`
- Modify docker-compose.yml to use alternative ports in the same range
- Update this documentation with the new port

### Kubernetes conflicts
If running on a Kubernetes cluster:
- Use NodePort services to expose specific ports
- Or use port-forwarding for local development
- Ensure the 14000-14099 range is not used by cluster services

## Verification Commands

```bash
# Check all EMCIP ports are available
for port in 9080 9081 9082 9083 9084 9085 9086 9087 14001 14002 14003 14004 14005 14006 14007 14008 14009; do
  if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null ; then
    echo "Port $port is IN USE"
  else
    echo "Port $port is FREE"
  fi
done
```
