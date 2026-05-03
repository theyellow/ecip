#!/usr/bin/env bash
# build-images.sh — Build and push EMCIP Docker images.
# Must be run from the project root (Dockerfiles use '.' as build context).
#
# Usage:
#   scripts/build-images.sh --native amd64   # native-amd64 for GraalVM services + tdlib-adapter:latest
#   scripts/build-images.sh --jvm            # :latest (JVM) for all services
#   scripts/build-images.sh --all            # both of the above
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

build_native_amd64() {
  echo "=== Building :native-amd64 images (GraalVM) ==="
  for entry in "${SERVICES_NATIVE[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    echo "--> emcip/${svc}:native-amd64  (${module}/Dockerfile.native)"
    docker build -f "${module}/Dockerfile.native" -t "emcip/${svc}:native-amd64" .
    docker push "emcip/${svc}:native-amd64"
    echo "    Pushed emcip/${svc}:native-amd64"
  done
  echo "--> emcip/tdlib-adapter:latest  (emcip-tdlib-adapter/Dockerfile, libtdjni.so compiled amd64)"
  docker build -f "emcip-tdlib-adapter/Dockerfile" -t "emcip/tdlib-adapter:latest" .
  docker push "emcip/tdlib-adapter:latest"
  echo "    Pushed emcip/tdlib-adapter:latest"
}

build_jvm() {
  echo "=== Building :latest (JVM) images ==="
  for entry in "${SERVICES_ALL[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    echo "--> emcip/${svc}:latest  (${module}/Dockerfile)"
    docker build -f "${module}/Dockerfile" -t "emcip/${svc}:latest" .
    docker push "emcip/${svc}:latest"
    echo "    Pushed emcip/${svc}:latest"
  done
}

usage() {
  echo "Usage: $0 --native amd64 | --jvm | --all" >&2
  exit 1
}

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
