#!/usr/bin/env bash
# Usage: ./scripts/db/restore.sh <backup_file>
set -euo pipefail
if [ $# -ne 1 ]; then
  echo "Usage: $0 <backup_file>"
  exit 1
fi
BACKUP_FILE="$1"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-14005}"
DB_NAME="${DB_NAME:-emcip}"
DB_USER="${DB_USER:-emcip}"
PGPASSWORD="${PGPASSWORD:-emcip}"
if [ ! -f "${BACKUP_FILE}" ]; then
  echo "[restore] Error: backup file not found: ${BACKUP_FILE}"
  exit 1
fi
echo "[restore] Restoring ${BACKUP_FILE} to ${DB_NAME}..."
PGPASSWORD="${PGPASSWORD}" pg_restore \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --dbname="${DB_NAME}" \
  --clean \
  --if-exists \
  "${BACKUP_FILE}"
echo "[restore] Done."
