#!/usr/bin/env bash
set -euo pipefail
API_BASE="${1:-http://localhost/api}"
JSON_FILE="${2:-$(dirname "$0")/sample-upload-v1.json}"

curl -s -X POST "$API_BASE/sync/upload" \
  -H 'Content-Type: application/json' \
  --data-binary "@$JSON_FILE"

echo
