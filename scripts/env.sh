#!/usr/bin/env bash
# Sourced by launchers. Prefer an explicit Java home, then PATH, then macOS JDK discovery.
STUDIO_JAVA_BIN=""
for candidate in "${JAVA_HOME:-}/bin/java" "$(command -v java || true)" /opt/homebrew/opt/openjdk@21/bin/java /usr/local/opt/openjdk@21/bin/java; do
  if [ -x "$candidate" ]; then
    major="$("$candidate" -version 2>&1 | awk -F '[".]' '/version/ {print $2;exit}')"
    if [[ "$major" =~ ^[0-9]+$ ]] && [ "$major" -ge 21 ]; then STUDIO_JAVA_BIN="$candidate"; break; fi
  fi
done
if [ -z "$STUDIO_JAVA_BIN" ] && [ -x /usr/libexec/java_home ]; then
  found_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [ -x "$found_jdk/bin/java" ]; then STUDIO_JAVA_BIN="$found_jdk/bin/java"; fi
fi
[ -n "$STUDIO_JAVA_BIN" ] || { echo 'Java 21+ is required. Set JAVA_HOME to a JDK 21+ installation.' >&2; exit 1; }
export JAVA_HOME="$(cd "$(dirname "$STUDIO_JAVA_BIN")/.." && pwd)"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"
