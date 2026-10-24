#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
source "$ROOT/scripts/env.sh"
command -v java >/dev/null || { echo 'Java 21+ is required'; exit 1; }
command -v node >/dev/null || { echo 'Node.js 22+ is required'; exit 1; }
export STUDIO_API_PORT="${STUDIO_API_PORT:-8080}"
export PORT="${PORT:-3000}"
export STUDIO_API_URL="http://127.0.0.1:$STUDIO_API_PORT"
export STUDIO_ORIGINS="http://localhost:$PORT,http://127.0.0.1:$PORT"
export NEXT_TELEMETRY_DISABLED=1
mkdir -p data
if [ ! -d frontend/node_modules ]; then (cd frontend && npm ci --no-fund); fi
./mvnw -q -f backend/pom.xml package -DskipTests
java -jar backend/target/studio-os-0.1.0.jar > data/backend.log 2>&1 &
BACKEND_PID=$!
FRONTEND_PID=''
cleanup() { if [ -n "$FRONTEND_PID" ]; then kill "$FRONTEND_PID" 2>/dev/null || true; fi; kill "$BACKEND_PID" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM
READY=0
for i in {1..60}; do
  kill -0 "$BACKEND_PID" 2>/dev/null || { tail -40 data/backend.log; exit 1; }
  if curl -fsS "http://127.0.0.1:$STUDIO_API_PORT/api/health" >/dev/null 2>&1; then READY=1; break; fi
  sleep 1
done
[ "$READY" = 1 ] || { echo 'Backend startup timed out. See data/backend.log'; exit 1; }
echo "Studio OS: http://localhost:$PORT"
(cd frontend && exec node node_modules/next/dist/bin/next dev --hostname 127.0.0.1 --port "$PORT") &
FRONTEND_PID=$!
wait "$FRONTEND_PID"
