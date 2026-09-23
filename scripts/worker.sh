#!/usr/bin/env bash
# Starts a worker-only process (no HTTP server, no scheduler).
# Build first with: mvn -q -DskipTests package
# Usage: scripts/worker.sh <worker-name> [concurrency]
set -euo pipefail

NAME="${1:?usage: scripts/worker.sh <worker-name> [concurrency]}"
CONCURRENCY="${2:-1}"
JAR="$(ls target/conveyor-ci-*.jar | grep -v original | head -1)"

exec java -jar "$JAR" \
  --spring.main.web-application-type=none \
  --conveyor.scheduler.enabled=false \
  --conveyor.worker.id="$NAME" \
  --conveyor.worker.concurrency="$CONCURRENCY"
