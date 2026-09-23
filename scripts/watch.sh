#!/usr/bin/env bash
# Prints a run's job statuses every second until the run finishes.
# Usage: scripts/watch.sh <run-id> [api-url]
set -euo pipefail

RUN_ID="${1:?usage: scripts/watch.sh <run-id> [api-url]}"
API="${2:-http://localhost:8080}"

while true; do
  OUT="$(curl -s "$API/api/runs/$RUN_ID" | python3 -c '
import json, sys
run = json.load(sys.stdin)
print("run #%s: %s" % (run["runNumber"], run["status"]))
for j in run["jobs"]:
    worker = "  on " + j["workerId"] if j.get("workerId") else ""
    reason = "  (" + j["failureReason"] + ")" if j.get("failureReason") else ""
    print("  %-14s %-10s attempt %s/%s%s%s" % (j["name"], j["status"], j["attempt"], j["maxAttempts"], worker, reason))
')"
  clear
  date '+%H:%M:%S'
  echo "$OUT"
  case "$(echo "$OUT" | head -1)" in
    *SUCCEEDED|*FAILED|*CANCELLED) break ;;
  esac
  sleep 1
done
