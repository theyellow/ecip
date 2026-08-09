#!/usr/bin/env bash
# Encrypts secret columns that still hold pre-encryption plaintext, in place.
#
# Step 3 of the migration runbook in docs/operations/secrets-encryption.md, as a script rather
# than a sequence of copy-pasted psql commands. Reads each legacy value, encrypts it with the
# SAME key the services use (read from the emcip-secrets Secret), and UPDATEs by primary key.
#
# Dry-run by default: prints what it would change and touches nothing. Pass --apply to write.
#
# Why this exists at all: a legacy value fails closed on read, and the "re-enter it through the
# Admin UI" repair does not cover every column. `telegram_accounts.api_hash` in particular is
# accepted only when an account is CREATED — there is no update endpoint and no edit form — so a
# legacy row there cannot be repaired through the UI at all.
#
# Never prints a secret, in plaintext or ciphertext.
set -euo pipefail

NS="${NS:-emcip}"
SECRET="${SECRET:-emcip-secrets}"
KEY_NAME="emcip-secret-key"
KUBECTL="${KUBECTL:-microk8s.kubectl}"
CORE_JAR="${CORE_JAR:-$HOME/.m2/repository/io/emcip/emcip-core/0.1.0-SNAPSHOT/emcip-core-0.1.0-SNAPSHOT.jar}"
JAVA="${JAVA:-${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/java}"

# table:column pairs. Order is irrelevant; each is handled independently.
COLUMNS=(
  "telegram_accounts:api_hash"
  "telegram_accounts:session_string"
  "llm_provider_configs:api_key"
  "ke_vendor_api_keys:api_key"
)

APPLY=false
case "${1:-}" in
  --apply) APPLY=true ;;
  ""|--dry-run) ;;
  *) echo "Usage: $0 [--dry-run|--apply]" >&2; exit 2 ;;
esac

command -v "$KUBECTL" >/dev/null || { echo "ERROR: $KUBECTL not found" >&2; exit 1; }
[ -x "$JAVA" ]   || { echo "ERROR: java not found at $JAVA (set JAVA=...)" >&2; exit 1; }
[ -f "$CORE_JAR" ] || { echo "ERROR: emcip-core jar not found at $CORE_JAR." >&2
                        echo "       Build it first: mvn -pl emcip-core -am install -DskipTests" >&2; exit 1; }

PGPOD="${PGPOD:-$($KUBECTL get pods -n "$NS" -l app=postgres -o name 2>/dev/null | head -1 | cut -d/ -f2)}"
[ -n "$PGPOD" ] || PGPOD="emcip-postgres-0"
$KUBECTL get pod "$PGPOD" -n "$NS" >/dev/null 2>&1 \
  || { echo "ERROR: postgres pod '$PGPOD' not found in namespace $NS (set PGPOD=...)" >&2; exit 1; }

KEY="$($KUBECTL get secret "$SECRET" -n "$NS" -o jsonpath="{.data.$KEY_NAME}" 2>/dev/null | base64 -d)"
[ -n "$KEY" ] || { echo "ERROR: '$KEY_NAME' missing from secret/$SECRET — run scripts/add-secret-key.sh" >&2; exit 1; }

psql_q() { $KUBECTL exec -n "$NS" "$PGPOD" -- psql -U emcip -d emcip -tAq -c "$1"; }

encrypt() {
  # The value reaches java as an argv, so it is briefly visible in the process list on this host.
  # Accepted: the same value is currently sitting in the database as plaintext, which is strictly
  # worse and is exactly what this script is removing.
  EMCIP_SECRET_KEY="$KEY" "$JAVA" -cp "$CORE_JAR" io.emcip.common.crypto.SecretCipherCli encrypt "$1"
}

$APPLY || echo "DRY RUN — nothing will be written. Re-run with --apply to change data."
echo

total_legacy=0
total_changed=0

for pair in "${COLUMNS[@]}"; do
  table="${pair%%:*}"
  column="${pair##*:}"

  # A table owned by a service that was never deployed here is not an error.
  exists="$(psql_q "select to_regclass('public.$table') is not null;" | tr -d '[:space:]')"
  if [ "$exists" != "t" ]; then
    printf '%-40s skipped (table not present)\n' "$table.$column"
    continue
  fi

  ids="$(psql_q "select id from $table where $column is not null and $column not like 'v1:%' order by id;")"
  ids="$(echo "$ids" | sed '/^[[:space:]]*$/d')"

  if [ -z "$ids" ]; then
    printf '%-40s ok (nothing to do)\n' "$table.$column"
    continue
  fi

  count="$(echo "$ids" | wc -l)"
  total_legacy=$((total_legacy + count))
  printf '%-40s %s legacy row(s)\n' "$table.$column" "$count"

  $APPLY || continue

  while IFS= read -r id; do
    [ -n "$id" ] || continue
    plaintext="$(psql_q "select $column from $table where id = '$id';")"
    cipher="$(encrypt "$plaintext")"
    case "$cipher" in
      v1:*) ;;
      *) echo "  ERROR: encryption produced no v1: prefix for $table.$column id=$id — aborting" >&2; exit 1 ;;
    esac
    # Ciphertext is v1: + base64, so it contains no quote characters.
    psql_q "update $table set $column = '$cipher' where id = '$id';" >/dev/null
    total_changed=$((total_changed + 1))
    echo "  encrypted id=$id"
  done <<< "$ids"
done

echo
if ! $APPLY; then
  echo "Found $total_legacy legacy value(s). Re-run with --apply to encrypt them."
  exit 0
fi

# Verify rather than trust: re-check every column and fail loudly if anything is still plaintext.
remaining=0
for pair in "${COLUMNS[@]}"; do
  table="${pair%%:*}"
  column="${pair##*:}"
  exists="$(psql_q "select to_regclass('public.$table') is not null;" | tr -d '[:space:]')"
  [ "$exists" = "t" ] || continue
  n="$(psql_q "select count(*) from $table where $column is not null and $column not like 'v1:%';" | tr -d '[:space:]')"
  remaining=$((remaining + n))
done

if [ "$remaining" -ne 0 ]; then
  echo "ERROR: $remaining value(s) are still unencrypted after the run." >&2
  exit 1
fi

echo "OK: encrypted $total_changed value(s); no unencrypted secrets remain."
echo
echo "No restart is needed for admin-api or llm-orchestrator: both decrypt per request, against"
echo "the row as read, so the next call already sees the repaired value."
echo
echo "Restart tdlib-adapter only if it is holding a session it established before the repair:"
echo "  $KUBECTL rollout restart statefulset/emcip-tdlib-adapter -n $NS"
