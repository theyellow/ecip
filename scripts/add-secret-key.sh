#!/usr/bin/env bash
# Generates the AES-256 secrets-encryption key and adds it to the emcip-secrets
# Secret as the key `emcip-secret-key`.
#
# It is a KEY INSIDE emcip-secrets, not a Secret of its own — the chart wires it as
# secretKeyRef{name: emcip-secrets, key: emcip-secret-key}, and the ref is not optional,
# so admin-api / knowledge-engine / llm-orchestrator will not start without it.
#
# See docs/operations/secrets-encryption.md. There is NO recovery if the key is lost:
# every encrypted value becomes unreadable. Back it up before continuing.
set -euo pipefail

NS="${NS:-emcip}"
SECRET="${SECRET:-emcip-secrets}"
KEY_NAME="emcip-secret-key"
KUBECTL="${KUBECTL:-microk8s.kubectl}"

command -v "$KUBECTL" >/dev/null || { echo "ERROR: $KUBECTL not found" >&2; exit 1; }
command -v openssl  >/dev/null || { echo "ERROR: openssl not found" >&2; exit 1; }

$KUBECTL get secret "$SECRET" -n "$NS" >/dev/null 2>&1 \
  || { echo "ERROR: secret/$SECRET not found in namespace $NS" >&2; exit 1; }

# Refuse to silently replace an existing key: that would orphan every value already
# encrypted with the old one, with no way back.
if $KUBECTL get secret "$SECRET" -n "$NS" -o jsonpath="{.data.$KEY_NAME}" 2>/dev/null | grep -q .; then
  echo "ERROR: '$KEY_NAME' already exists in secret/$SECRET." >&2
  echo "       Replacing it makes every value encrypted with the old key unreadable." >&2
  echo "       Delete it deliberately first if that is really what you want." >&2
  exit 1
fi

KEY=$(openssl rand -base64 32)

echo "================================================================"
echo " BACK THIS UP NOW — there is no recovery if it is lost:"
echo
echo "   $KEY"
echo
echo " Store it somewhere separate from the database. A backup holding"
echo " both the key and the data defeats the encryption."
echo "================================================================"

$KUBECTL patch secret "$SECRET" -n "$NS" --type=json \
  -p="[{\"op\":\"add\",\"path\":\"/data/$KEY_NAME\",\"value\":\"$(printf %s "$KEY" | base64 -w0)\"}]" >/dev/null

# Verify rather than trust the patch: a mangled value fails much later, in a
# confusing place. Stored value is base64(Secret) of a base64 string, hence two decodes.
size=$($KUBECTL get secret "$SECRET" -n "$NS" -o jsonpath="{.data.$KEY_NAME}" \
        | base64 -d | base64 -d | wc -c)
if [ "$size" -ne 32 ]; then
  echo "ERROR: stored key decodes to $size bytes, expected 32. Not usable." >&2
  exit 1
fi

echo "OK: '$KEY_NAME' added to secret/$SECRET (verified: decodes to 32 bytes)."
echo
echo "Next: run 'helm upgrade' so the deployments actually reference it —"
echo "an existing release predating this key has no EMCIP_SECRET_KEY env var at all."
