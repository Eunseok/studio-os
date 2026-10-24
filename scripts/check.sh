#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./mvnw -q -f backend/pom.xml test
(cd frontend && npm run build)
