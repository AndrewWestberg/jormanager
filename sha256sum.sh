#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [version]" >&2
  exit 1
fi

if [[ $# -eq 1 ]]; then
  VERSION="${1#v}"
else
  VERSION="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)"[[:space:]]*$/\1/p' build.gradle.kts)"
fi

if [[ -z "$VERSION" || "$VERSION" == *plain* || "$VERSION" == */* ]]; then
  echo "Error: invalid version '${VERSION}'." >&2
  exit 1
fi

LIBS_DIR="build/libs"
JAR_NAME="jormanager-${VERSION}.jar"
CHECKSUM_NAME="${JAR_NAME}.sha256"

if [[ ! -f "${LIBS_DIR}/${JAR_NAME}" ]]; then
  echo "Error: executable JAR '${LIBS_DIR}/${JAR_NAME}' not found. Run './gradlew bootJar' first." >&2
  exit 1
fi

pushd "$LIBS_DIR" >/dev/null
trap 'popd >/dev/null || true' EXIT

sha256sum "$JAR_NAME" > "$CHECKSUM_NAME"
sha256sum -c "$CHECKSUM_NAME" >/dev/null
echo "$CHECKSUM_NAME"
echo "---------------------------------"
cat "$CHECKSUM_NAME"
