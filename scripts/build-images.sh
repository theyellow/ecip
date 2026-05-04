#!/usr/bin/env bash
# build-images.sh — Build and push EMCIP Docker images.
# Must be run from the project root (Dockerfiles use '.' as build context).
#
# Usage:
#   scripts/build-images.sh [--registry <prefix>] --native amd64 | --jvm | --all
#
# Examples:
#   scripts/build-images.sh --native amd64                             # local: emcip/<svc>:native-amd64
#   scripts/build-images.sh --jvm                                      # local: emcip/<svc>:latest
#   scripts/build-images.sh --jvm --registry ghcr.io/theyellow/ecip   # CI: ghcr.io/theyellow/ecip/<svc>:latest
#   scripts/build-images.sh --all --registry ghcr.io/theyellow/ecip   # all images with ghcr.io prefix
set -euo pipefail

# module/image-name pairs for all services
SERVICES_ALL=(
  "emcip-conversation-context/conversation-context"
  "emcip-intent-classifier/intent-classifier"
  "emcip-policy-engine/policy-engine"
  "emcip-llm-orchestrator/llm-orchestrator"
  "emcip-moderation-service/moderation-service"
  "emcip-audit-service/audit-service"
  "emcip-admin-api/admin-api"
  "emcip-admin-ui/admin-ui"
  "emcip-tdlib-adapter/tdlib-adapter"
)

# Services that have a Dockerfile.native (GraalVM native image)
SERVICES_NATIVE=(
  "emcip-conversation-context/conversation-context"
  "emcip-policy-engine/policy-engine"
  "emcip-llm-orchestrator/llm-orchestrator"
)

REGISTRY=""

is_native_capable() {
  local svc="$1"
  for entry in "${SERVICES_NATIVE[@]}"; do
    [[ "${entry##*/}" == "$svc" ]] && return 0
  done
  return 1
}

image_tag() {
  local svc="$1" tag="$2"
  if [[ -n "$REGISTRY" ]]; then
    echo "${REGISTRY}/${svc}:${tag}"
  else
    echo "emcip/${svc}:${tag}"
  fi
}

build_native_amd64() {
  echo "=== Building :native-amd64 images (GraalVM) ==="
  for entry in "${SERVICES_NATIVE[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    tag="$(image_tag "$svc" "native-amd64")"
    echo "--> ${tag}  (${module}/Dockerfile.native)"
    docker build -f "${module}/Dockerfile.native" -t "${tag}" .
    docker push "${tag}"
    echo "    Pushed ${tag}"
  done
  tdlib_tag="$(image_tag "tdlib-adapter" "latest")"
  echo "--> ${tdlib_tag}  (emcip-tdlib-adapter/Dockerfile, libtdjni.so compiled amd64)"
  docker build -f "emcip-tdlib-adapter/Dockerfile" -t "${tdlib_tag}" .
  docker push "${tdlib_tag}"
  echo "    Pushed ${tdlib_tag}"
}

build_jvm() {
  echo "=== Building JVM images ==="
  for entry in "${SERVICES_ALL[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    if [[ -n "$REGISTRY" ]] && is_native_capable "$svc"; then
      tag="$(image_tag "$svc" "jvm-latest")"
    else
      tag="$(image_tag "$svc" "latest")"
    fi
    echo "--> ${tag}  (${module}/Dockerfile)"
    docker build -f "${module}/Dockerfile" -t "${tag}" .
    docker push "${tag}"
    echo "    Pushed ${tag}"
  done
}

usage() {
  echo "Usage: $0 [--registry <prefix>] --native amd64 | --jvm | --all" >&2
  exit 1
}

# Parse optional --registry flag
if [[ "${1:-}" == "--registry" ]]; then
  [[ -n "${2:-}" ]] || { echo "Error: --registry requires a value" >&2; usage; }
  REGISTRY="${2%/}"
  shift 2
fi

[[ $# -eq 0 ]] && usage

case "$1" in
  --native)
    [[ "${2:-}" == "amd64" ]] || { echo "Error: --native requires 'amd64' argument" >&2; usage; }
    build_native_amd64
    ;;
  --jvm)
    build_jvm
    ;;
  --all)
    build_native_amd64
    build_jvm
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage
    ;;
esac
