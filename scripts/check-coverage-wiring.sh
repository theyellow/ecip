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

# Excluded classes must NOT appear in any coverage report.
# jacoco.csv columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,... so PACKAGE is $2
# and CLASS is $3. Match on those columns explicitly — a naive line-anchored
# regex like ',(dto|entity)$' can never match (every line ends in a number) and
# so passes vacuously whether the excludes work or not.
excluded_hits() {
  awk -F, 'NR>1 && ($2 ~ /\.(dto|entity)$/ \
                 || $3 ~ /Application$/ \
                 || ($2 ~ /\.config$/ && $3 ~ /Properties$/))' \
    */target/site/jacoco/jacoco.csv 2>/dev/null
}

if excluded_hits | grep -q .; then
  echo "EXCLUDE FAILED: excluded classes still counted:"; excluded_hits | head -5; fail=1
fi

# Self-test: prove the matcher above can actually fail. A checker that cannot
# distinguish a violation from a clean run is worse than no checker.
if ! printf 'GROUP,PACKAGE,CLASS,A\nx,io.emcip.foo.entity,Bar,1\n' \
     | awk -F, 'NR>1 && $2 ~ /\.(dto|entity)$/' | grep -q .; then
  echo "SELF-TEST FAILED: exclude matcher cannot detect a known violation"; fail=1
fi

[ "$fail" -eq 0 ] && echo "coverage wiring OK"
exit "$fail"
