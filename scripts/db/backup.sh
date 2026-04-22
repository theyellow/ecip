#!/usr/bin/env bash
# Usage: ./scripts/db/backup.sh [output_dir]
set -euo pipefail
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-14005}"
DB_NAME="${DB_NAME:-emcip}"
DB_USER="${DB_USER:-emcip}"
PGPASSWORD="${PGPASSWORD:-emcip}"
OUTPUT_DIR="${1:-./backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="${OUTPUT_DIR}/emcip_${TIMESTAMP}.dump"
mkdir -p "${OUTPUT_DIR}"
echo "[backup] Starting backup of ${DB_NAME} to ${OUTPUT_FILE}..."
PGPASSWORD="${PGPASSWORD}" pg_dump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --format=custom \
  --compress=9 \
  "${DB_NAME}" \
  --file="${OUTPUT_FILE}"
BACKUP_SIZE=$(du -sh "${OUTPUT_FILE}" | cut -f1)
echo "[backup] Done. File: ${OUTPUT_FILE} (${BACKUP_SIZE})"
