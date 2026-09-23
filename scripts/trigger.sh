#!/usr/bin/env bash
# Triggers a run of a pipeline file for a project.
# Usage: scripts/trigger.sh <project-id> <pipeline.yml> [api-url]
set -euo pipefail

PROJECT_ID="${1:?usage: scripts/trigger.sh <project-id> <pipeline.yml> [api-url]}"
PIPELINE="${2:?usage: scripts/trigger.sh <project-id> <pipeline.yml> [api-url]}"
API="${3:-http://localhost:8080}"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo abcdef1)"

python3 -c '
import json, sys
print(json.dumps({"commitSha": sys.argv[1], "branch": "main", "pipelineYaml": open(sys.argv[2]).read()}))
' "$SHA" "$PIPELINE" |
  curl -s -X POST "$API/api/projects/$PROJECT_ID/runs" -H 'Content-Type: application/json' -d @- |
  python3 -c '
import json, sys
run = json.load(sys.stdin)
if "id" not in run:
    print(json.dumps(run, indent=2)); sys.exit(1)
print("run #%s (id %s) %s  stages: %s" % (run["runNumber"], run["id"], run["status"], run["stages"]))
print("watch it:  scripts/watch.sh %s" % run["id"])
'
