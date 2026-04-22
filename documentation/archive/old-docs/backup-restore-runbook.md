# EMCIP PostgreSQL Backup & Restore Runbook

## Overview

EMCIP provides two shell scripts for backing up and restoring the PostgreSQL database. Both scripts use `pg_dump`/`pg_restore` in the custom compressed format (`-Fc`), which supports parallel restore and partial table selection.

## Scripts

| Script | Location |
|--------|----------|
| Backup | `scripts/db/backup.sh` |
| Restore | `scripts/db/restore.sh` |

## Environment Variables

Both scripts read connection settings from environment variables with sensible defaults for local development:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `14005` | PostgreSQL port |
| `DB_NAME` | `emcip` | Database name |
| `DB_USER` | `emcip` | PostgreSQL username |
| `PGPASSWORD` | `emcip` | PostgreSQL password |

## Usage

### Create a Backup

```bash
# Backup to default ./backups directory
./scripts/db/backup.sh

# Backup to a custom directory
./scripts/db/backup.sh /var/backups/emcip
```

The script creates a timestamped file: `emcip_YYYYMMDD_HHMMSS.dump`.

Example output:

```
[backup] Starting backup of emcip to ./backups/emcip_20260422_143000.dump...
[backup] Done. File: ./backups/emcip_20260422_143000.dump (2.4M)
```

### Restore from a Backup

```bash
# Restore a specific dump file
./scripts/db/restore.sh ./backups/emcip_20260422_143000.dump
```

The restore uses `--clean --if-exists` to drop and recreate objects before restoring, allowing it to run against a non-empty database.

Example output:

```
[restore] Restoring ./backups/emcip_20260422_143000.dump to emcip...
[restore] Done.
```

### Override Connection Settings

```bash
DB_HOST=prod-db.internal DB_PORT=5432 DB_NAME=emcip_prod \
  DB_USER=emcip_admin PGPASSWORD=secret \
  ./scripts/db/backup.sh /mnt/backups
```

## Automated Backups

To schedule daily backups via cron:

```cron
0 2 * * * /opt/emcip/scripts/db/backup.sh /var/backups/emcip >> /var/log/emcip-backup.log 2>&1
```

To retain only the last 7 days of backups, add after the backup command:

```bash
find /var/backups/emcip -name "*.dump" -mtime +7 -delete
```

## Integration Test

The `BackupRestoreIT` class in `emcip-policy-engine` verifies end-to-end backup and restore behaviour:

1. Counts rows in `policy_rules` and `policy_decisions`.
2. Runs `backup.sh` and asserts a `.dump` file is produced.
3. Truncates both tables.
4. Runs `restore.sh` and asserts row counts are restored.

### Running the Integration Test

The test is gated by an environment variable to prevent accidental execution against a live database:

```bash
# Requires Docker Compose stack to be running
docker compose up -d postgres

# Run only the backup-restore tests
ECIP_IT_ENABLED=true mvn test -pl emcip-policy-engine \
  -Dgroups=backup-restore \
  --no-transfer-progress
```

### Prerequisites

- `pg_dump` and `pg_restore` must be on `PATH` (same major version as the server).
- The PostgreSQL container must be reachable on `localhost:14005`.
- The `emcip` role must have `SUPERUSER` or at minimum `pg_restore`-compatible privileges.

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `pg_dump: error: connection failed` | Wrong host/port or DB not running | Check `DB_HOST`/`DB_PORT` and `docker compose ps` |
| `pg_restore: error: input file appears to be a text format dump` | Dump was created with `--format=plain` | Re-run backup; scripts always use `--format=custom` |
| `permission denied` on restore | User lacks DROP/CREATE rights | Grant `SUPERUSER` or run as the owner of the schema |
| Backup file is 0 bytes | `pg_dump` exited non-zero but `set -e` caught it | Check exit code and PostgreSQL server logs |
