#!/usr/bin/env bash
# Prints measured line coverage and the ratchet floor (measured - 2, rounded
# down) for every module. Run AFTER a full `mvn -B clean verify`.
set -euo pipefail

printf '%-30s %8s %8s\n' MODULE MEASURED FLOOR
for f in */target/site/jacoco/jacoco.csv; do
  [ -f "$f" ] || continue
  m="${f%%/*}"
  awk -F, -v m="$m" 'NR>1 { miss += $8; cov += $9 }
    END {
      if (miss + cov > 0) {
        pct = 100 * cov / (miss + cov);
        floor = int(pct) - 2;
        if (floor < 0) floor = 0;
        printf "%-30s %7.1f%% %7s\n", m, pct, sprintf("0.%02d", floor);
      }
    }' "$f"
done
