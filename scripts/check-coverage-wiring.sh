#!/usr/bin/env bash
# Verifies JaCoCo is wired in every Java module and that unit and integration
# execution data are captured SEPARATELY (the argLine collision regression).
set -euo pipefail

MODULES="emcip-core emcip-tdlib-adapter emcip-conversation-context emcip-intent-classifier \
emcip-policy-engine emcip-llm-orchestrator emcip-moderation-service emcip-audit-service \
emcip-admin-api emcip-admin-ui emcip-knowledge-engine"

fail=0
for m in $MODULES; do
  for f in jacoco.exec jacoco-merged.exec; do
    if [ ! -s "$m/target/$f" ]; then
      echo "MISSING or EMPTY: $m/target/$f"; fail=1
    fi
  done
  if [ ! -s "$m/target/site/jacoco/jacoco.csv" ]; then
    echo "MISSING report: $m/target/site/jacoco/jacoco.csv"; fail=1
  fi
done

# Only these five modules contain *IT/*IntegrationTest classes, so only they can
# produce IT execution data. Both exec files present and non-empty in the same
# module proves the two agents wrote separately and the argLine properties did
# not collide. (emcip-admin-api has 45 tests but zero ITs.)
for m in emcip-audit-service emcip-conversation-context emcip-knowledge-engine \
         emcip-moderation-service emcip-policy-engine; do
  if [ ! -s "$m/target/jacoco-it.exec" ]; then
    echo "MISSING or EMPTY: $m/target/jacoco-it.exec (argLine collision?)"; fail=1
  fi
done

[ "$fail" -eq 0 ] && echo "coverage wiring OK"
exit "$fail"
