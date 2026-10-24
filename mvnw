#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT/scripts/env.sh"
if command -v mvn >/dev/null 2>&1; then exec mvn "$@"; fi
VERSION=3.9.11
DEST="$ROOT/.tools/apache-maven-$VERSION"
if [ ! -x "$DEST/bin/mvn" ]; then
  command -v curl >/dev/null || { echo 'curl is required'; exit 1; }
  mkdir -p "$ROOT/.tools"
  ARCHIVE="$ROOT/.tools/maven-$VERSION.tar.gz"
  curl --fail --location --retry 3 "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz" -o "$ARCHIVE"
  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL="$(sha512sum "$ARCHIVE" | cut -d ' ' -f 1)"
  else
    ACTUAL="$(shasum -a 512 "$ARCHIVE" | cut -d ' ' -f 1)"
  fi
  [ "$ACTUAL" = "bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978" ] || { echo 'Maven checksum mismatch'; exit 1; }
  tar -xzf "$ARCHIVE" -C "$ROOT/.tools"
  rm "$ARCHIVE"
fi
exec "$DEST/bin/mvn" "$@"
