# Local Infrastructure Setup

This document describes how to set up and manage the local development infrastructure for EMCIP.

## Overview

The local development environment uses Docker Compose to run:
- **Apache Kafka** (with Zookeeper) - Event backbone
- **PostgreSQL** - Persistent storage
- **Kafka UI** - Web UI for monitoring Kafka (optional)
- **pgAdmin** - Web UI for managing PostgreSQL (optional)

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+
- Available ports: 14001-14006 (see [PORT_CONFIGURATION.md](../PORT_CONFIGURATION.md))

## Quick Start

### Start All Services

```bash
docker-compose up -d
```

### Check Service Status

```bash
docker-compose ps
```

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f kafka
docker-compose logs -f postgres
```

### Stop Services

```bash
# Stop but keep data
docker-compose stop

# Stop and remove containers
docker-compose down

# Stop and remove containers + volumes (⚠️ deletes data)
docker-compose down -v
```

## Services

### Kafka

- **Broker:** `localhost:14002` (internal), `localhost:14003` (external)
- **Zookeeper:** `localhost:14001`
- **Partitions:** 3 (default)
- **Auto-create topics:** Enabled

**Test Kafka:**
```bash
# Create a topic
docker exec ecip-kafka kafka-topics --create --topic test-topic --bootstrap-server localhost:14003 --replication-factor 1 --partitions 3

# List topics
docker exec ecip-kafka kafka-topics --list --bootstrap-server localhost:14003

# Produce messages
docker exec -it ecip-kafka kafka-console-producer --topic test-topic --bootstrap-server localhost:14003

# Consume messages
docker exec -it ecip-kafka kafka-console-consumer --topic test-topic --from-beginning --bootstrap-server localhost:14003
```

### PostgreSQL

- **Host:** `localhost`
- **Port:** `14005`
- **Database:** `emcip`
- **Username:** `emcip`
- **Password:** `emcip`
- **JDBC URL:** `jdbc:postgresql://localhost:14005/emcip`
- **R2DBC URL:** `r2dbc:postgresql://localhost:14005/emcip`

**Connect via psql:**
```bash
docker exec -it ecip-postgres psql -U emcip -d emcip
```

**Test Connection:**
```bash
docker exec ecip-postgres pg_isready -U emcip
```

### Kafka UI (Optional)

- **URL:** http://localhost:14004
- **Features:** Browse topics, messages, consumer groups

### pgAdmin (Optional)

- **URL:** http://localhost:14006
- **Email:** `admin@ecip.io`
- **Password:** `admin`

**Add Server in pgAdmin:**
1. Open http://localhost:14006
2. Login with admin/admin
3. Right-click "Servers" → "Register" → "Server"
4. General tab: Name = "EMCIP Local"
5. Connection tab:
   - Host = `postgres`
   - Port = `14005`
   - Database = `emcip`
   - Username = `emcip`
   - Password = `emcip`

## Service Configuration

### Application Configuration

Update `application.yml` in services to connect:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:14003
  r2dbc:
    url: r2dbc:postgresql://localhost:14005/emcip
    username: emcip
    password: emcip
```

### Network

All services are on the `ecip-network` bridge network:
- Services can reach each other by container name (e.g., `kafka:14002`, `postgres:14005`)
- Host machine accesses via `localhost`

## Troubleshooting

### Kafka Connection Issues

```bash
# Check if Kafka is healthy
docker-compose ps kafka

# View Kafka logs
docker-compose logs kafka

# Restart Kafka
docker-compose restart kafka
```

### PostgreSQL Connection Issues

```bash
# Check if PostgreSQL is healthy
docker-compose ps postgres

# View PostgreSQL logs
docker-compose logs postgres

# Check if database is ready
docker exec ecip-postgres pg_isready -U emcip
```

### Port Conflicts

If ports are already in use:
1. Check what's using the port: `lsof -i :14005`
2. Stop conflicting service or modify `docker-compose.yml` ports

### Clean Slate

```bash
# Remove everything and start fresh
docker-compose down -v
docker-compose up -d
```

## Resource Usage

| Service | Memory | CPU |
|---------|--------|-----|
| Zookeeper | ~200MB | Low |
| Kafka | ~1GB | Medium |
| Kafka UI | ~200MB | Low |
| PostgreSQL | ~200MB | Low |
| pgAdmin | ~150MB | Low |
| **Total** | ~1.75GB | - |

## Next Steps

After infrastructure is running:
1. **US-1.3.2:** Define Kafka topics and event schemas
2. **US-1.3.4:** Set up Liquibase migrations
3. **US-1.3.5:** Implement health checks for infrastructure
