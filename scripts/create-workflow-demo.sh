#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$ROOT/examples/workflow-demo"
if [ -e "$REPO" ] && [ ! -f "$REPO/verify.py" ]; then
  echo "기존 경로를 확인하세요: $REPO (덮어쓰지 않습니다)" >&2
  exit 1
fi
if [ ! -d "$REPO" ]; then
mkdir -p "$REPO/.agents/skills/workflow-guide"
cat > "$REPO/README.md" <<'DOC'
# Workflow demo

Studio OS의 단계 승인과 선행 변경 전달을 확인하는 로컬 fixture입니다.
원본 저장소를 변경하지 않고 작업별 worktree에서 실행합니다.
DOC
cat > "$REPO/.agents/skills/workflow-guide/SKILL.md" <<'SKILL'
---
name: workflow-guide
description: 단계별 생성 결과와 검증 조건을 확인하는 데모 안내입니다.
version: 1.0.0
required-tools: [python3, git]
---
각 단계는 독립 worktree에서 실행합니다. 선행 변경을 유지하고 검증 결과를 정직하게 보고하세요.
Demo worker는 Skill을 실행하지 않고 고정 샘플을 생성합니다.
SKILL
cat > "$REPO/verify.py" <<'PY'
from pathlib import Path
import sys
assert Path("STUDIO_DEMO.md").exists(), "Demo artifact is missing"
if sys.argv[1] == "prepare":
    Path("HANDOFF.md").write_text("Approved predecessor output\n")
elif sys.argv[1] == "review":
    assert Path("HANDOFF.md").read_text() == "Approved predecessor output\n"
else:
    raise AssertionError("Use prepare or review")
print("Workflow verification passed: " + sys.argv[1])
PY
fi
if [ ! -d "$REPO/.git" ]; then
git -C "$REPO" init -q
git -C "$REPO" add .
git -C "$REPO" -c user.name='Studio demo' -c user.email='demo@example.invalid' -c core.hooksPath=/dev/null commit -qm 'Initialize workflow fixture'
fi
printf 'Workflow demo Git repository: %s\n' "$REPO"
echo '프로젝트 테스트 명령: ["python3","verify.py","prepare"]'
echo '단계 1: Demo worker, Skill workflow-guide, 프로젝트 테스트 상속'
echo '단계 2: Demo worker, 단계 1 의존, 테스트 ["python3","verify.py","review"]'
