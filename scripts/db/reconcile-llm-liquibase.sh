#!/usr/bin/env bash
# Reconciles llm-orchestrator's DATABASECHANGELOG with its changelog, so that the service's
# Liquibase - which never ran before LO-LIQUIBASE (Spring Boot 4 removed the auto-configuration and
# no LiquibaseConfig bean existed) - can start on an existing database without failing.
#
# Run ONCE per existing environment, BEFORE deploying the first image that contains
# emcip-llm-orchestrator's LiquibaseConfig. Fresh databases need nothing: Liquibase builds them.
#
# What it does, in one transaction:
#   1. Clears the stored checksum (md5sum -> NULL) of every llm-orchestrator changeset. The rows
#      were recorded by hand, 27 of them with a fake '9:manual' checksum that fails validation, and
#      LO-LIQUIBASE corrected several changesets (009 <where> placement, per_1k -> per1k column
#      names) so even the genuine checksums are stale. NULL makes Liquibase recompute; it does not
#      re-run anything.
#   2. Marks as MARK_RAN the 9 seed changesets in 004/005 that were never recorded. Their data was
#      superseded by later changesets and by operators; running them now would collide with
#      existing rows (e.g. prompt template 'auto_response') or resurrect retired model configs.
#   3. Leaves llm-16 (widen llm_provider_configs.api_key to TEXT) unrecorded, so Liquibase applies
#      it at the next boot - it is the one change the live schema genuinely lacks.
#
# Changeset ids are read from the changelog files in this repository, so the script matches on
# (id, filename) pairs and never touches another service's rows.
#
# Dry-run by default: prints what it would change and touches nothing. --rehearse runs the real
# statements inside a transaction, shows the resulting state, and ROLLS BACK. --apply writes.
# Idempotent: a second --apply changes nothing.
set -euo pipefail

MODE=dry-run
case "${1:-}" in
  --apply) MODE=apply ;;
  --rehearse) MODE=rehearse ;;
  ""|--dry-run) ;;
  *) echo "Usage: $0 [--dry-run|--rehearse|--apply]" >&2; exit 2 ;;
esac

NAMESPACE="${NAMESPACE:-emcip}"
PG_POD="${PG_POD:-emcip-postgres-0}"
KUBECTL="${KUBECTL:-microk8s.kubectl}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHANGES_DIR="$REPO_ROOT/emcip-llm-orchestrator/src/main/resources/db/changelog/changes"

# Seeds superseded by later changesets - recorded as MARK_RAN, never executed.
MARK_RAN_IDS=(
  004-seed-model-config-response
  004-seed-model-config-summary
  004-seed-model-config-command-validation
  004-seed-prompt-template-auto-response
  004-seed-prompt-template-escalation-summary
  004-seed-prompt-template-command-validation
  005-seed-sonnet-config-response
  005-seed-sonnet-config-summary
  005-seed-sonnet-config-command-validation
)

psql_q() {
  "$KUBECTL" exec -i -n "$NAMESPACE" "$PG_POD" -- \
    psql -U emcip emcip -v ON_ERROR_STOP=1 -At "$@" 2>&1 | cat
}

[ -d "$CHANGES_DIR" ] || { echo "Changelog not found: $CHANGES_DIR" >&2; exit 1; }

# (id, author, filename) of every llm-orchestrator changeset, from source.
PAIRS_SQL=""
for f in "$CHANGES_DIR"/*.xml; do
  file="db/changelog/changes/$(basename "$f")"
  while IFS=$'\t' read -r id author; do
    [ -n "$id" ] || continue
    PAIRS_SQL+="('$id','$author','$file'),"
  done < <(grep -o '<changeSet id="[^"]*" author="[^"]*"' "$f" \
             | sed -E 's/<changeSet id="([^"]*)" author="([^"]*)"/\1\t\2/')
done
PAIRS_SQL="${PAIRS_SQL%,}"
[ -n "$PAIRS_SQL" ] || { echo "No changesets parsed from $CHANGES_DIR" >&2; exit 1; }

MARK_LIST=$(printf "'%s'," "${MARK_RAN_IDS[@]}"); MARK_LIST="${MARK_LIST%,}"

STATUS_SQL="
WITH src(id, author, filename) AS (VALUES $PAIRS_SQL)
SELECT
  (SELECT count(*) FROM src) AS changesets_in_source,
  (SELECT count(*) FROM databasechangelog d JOIN src USING (id, filename)) AS recorded,
  (SELECT count(*) FROM databasechangelog d JOIN src USING (id, filename)
     WHERE d.md5sum IS NOT NULL) AS checksums_to_clear,
  (SELECT count(*) FROM databasechangelog d JOIN src USING (id, filename)
     WHERE d.md5sum = '9:manual') AS fake_checksums,
  (SELECT count(*) FROM src WHERE id IN ($MARK_LIST)
     AND NOT EXISTS (SELECT 1 FROM databasechangelog d
                     WHERE d.id = src.id AND d.filename = src.filename)) AS seeds_to_mark_ran,
  (SELECT count(*) FROM src
     WHERE NOT EXISTS (SELECT 1 FROM databasechangelog d
                       WHERE d.id = src.id AND d.filename = src.filename)
     AND id NOT IN ($MARK_LIST)) AS left_for_liquibase_to_run;"

echo "== Before"
psql_q -F ' | ' -P footer=off -c "$STATUS_SQL" | sed 's/^/   /'
echo "   (columns: in source | recorded | checksums to clear | fake '9:manual' | seeds to MARK_RAN | left for Liquibase to run)"
echo "   Left for Liquibase to run at next boot:"
psql_q -c "WITH src(id, author, filename) AS (VALUES $PAIRS_SQL)
  SELECT '     - ' || id || '  (' || filename || ')' FROM src
  WHERE NOT EXISTS (SELECT 1 FROM databasechangelog d WHERE d.id = src.id AND d.filename = src.filename)
    AND id NOT IN ($MARK_LIST);"

if [ "$MODE" = dry-run ]; then
  echo
  echo "Dry run - nothing changed. --rehearse to test the writes (rolled back), --apply to write."
  exit 0
fi

END=COMMIT
[ "$MODE" = rehearse ] && END=ROLLBACK
echo
echo "== ${MODE^}: one transaction, ending in $END"
psql_q -F ' | ' -P footer=off -c "BEGIN;
WITH src(id, author, filename) AS (VALUES $PAIRS_SQL)
UPDATE databasechangelog d SET md5sum = NULL
  FROM src WHERE d.id = src.id AND d.filename = src.filename AND d.md5sum IS NOT NULL;
WITH src(id, author, filename) AS (VALUES $PAIRS_SQL),
     todo AS (SELECT src.*, row_number() OVER (ORDER BY filename, id) AS n FROM src
              WHERE id IN ($MARK_LIST)
                AND NOT EXISTS (SELECT 1 FROM databasechangelog d
                                WHERE d.id = src.id AND d.filename = src.filename))
INSERT INTO databasechangelog
  (id, author, filename, dateexecuted, orderexecuted, exectype, md5sum, description,
   comments, liquibase, deployment_id)
SELECT id, author, filename, now(),
       (SELECT coalesce(max(orderexecuted), 0) FROM databasechangelog) + n,
       'MARK_RAN', NULL, 'reconcile-llm-liquibase.sh',
       'LO-LIQUIBASE: superseded seed, never executed', '4.x', 'lo-liquib'
  FROM todo;
$STATUS_SQL
$END;" | sed 's/^/   /'

if [ "$MODE" = rehearse ]; then
  echo "   (state inside the transaction, before ROLLBACK - expect 48 | 47 | 0 | 0 | 0 | 1)"
  echo
  echo "Rehearsal rolled back - nothing changed."
  exit 0
fi

echo
echo "== After"
psql_q -F ' | ' -P footer=off -c "$STATUS_SQL" | sed 's/^/   /'

# Self-verification: nothing left that would fail validation or collide on the next boot.
read -r _ _ CLEAR FAKE MARK LEFT < <(psql_q -F ' ' -P footer=off -c "$STATUS_SQL" | tr '|' ' ')
if [ "$CLEAR" != 0 ] || [ "$FAKE" != 0 ] || [ "$MARK" != 0 ]; then
  echo "FAILED: expected 0 checksums / 0 fake / 0 seeds pending, got $CLEAR / $FAKE / $MARK" >&2
  exit 1
fi
echo "OK: checksums cleared, seeds marked. Liquibase will run $LEFT changeset(s) at the next boot."
