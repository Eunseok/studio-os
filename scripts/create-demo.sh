#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/examples/hello-studio"
if [ -d "$DEST/.git" ]; then echo "$DEST"; exit 0; fi
mkdir -p "$DEST"
cd "$DEST"
git init -q
cat > README.md <<'EOF'
# Hello Studio
A disposable repository for Studio OS. Choose Demo worker to create STUDIO_DEMO.md without calling an AI service.
EOF
cat > verify.sh <<'EOF'
#!/bin/sh
set -eu
test -f STUDIO_DEMO.md
printf 'Demo artifact exists. Verification passed.\n'
EOF
git add README.md verify.sh
git -c user.name='Studio Demo' -c user.email='demo@example.invalid' -c core.hooksPath=/dev/null commit -qm 'Initial demo repository'
echo "$DEST"
