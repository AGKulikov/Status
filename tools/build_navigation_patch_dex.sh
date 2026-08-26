#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/navigator-mod/src/main/java"
OUTPUT_DIR="${1:-$PROJECT_DIR/build/navigation-mod}"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ]; then
  printf 'ANDROID_SDK_ROOT or ANDROID_HOME must point at an Android SDK\n' >&2
  exit 1
fi

ANDROID_JAR=""
while IFS= read -r candidate; do ANDROID_JAR="$candidate"; done < <(
  find "$SDK_ROOT/platforms" -mindepth 2 -maxdepth 2 -name android.jar -type f | sort -V
)
D8=""
while IFS= read -r candidate; do D8="$candidate"; done < <(
  find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -name d8 -type f | sort -V
)
if [ -z "$ANDROID_JAR" ] || [ -z "$D8" ]; then
  printf 'Android platform jar or d8 was not found under %s\n' "$SDK_ROOT" >&2
  exit 1
fi

BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/natro-navigation-dex.XXXXXX")"
trap 'rm -rf "$BUILD_ROOT"' EXIT HUP INT TERM
mkdir -p "$BUILD_ROOT/classes" "$BUILD_ROOT/dex" "$OUTPUT_DIR"

mapfile -d '' SOURCES < <(find "$SOURCE_DIR" -name '*.java' -type f -print0 | sort -z)
if [ "${#SOURCES[@]}" -eq 0 ]; then
  printf 'No Navigator patch Java sources found\n' >&2
  exit 1
fi

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -classpath "$ANDROID_JAR" -d "$BUILD_ROOT/classes" "${SOURCES[@]}"

mapfile -d '' CLASSES < <(find "$BUILD_ROOT/classes" -name '*.class' -type f -print0 | sort -z)
"$D8" --min-api 28 --lib "$ANDROID_JAR" --output "$BUILD_ROOT/dex" "${CLASSES[@]}"
install -m 0644 "$BUILD_ROOT/dex/classes.dex" "$OUTPUT_DIR/classes19.dex"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUTPUT_DIR/classes19.dex"
else
  shasum -a 256 "$OUTPUT_DIR/classes19.dex"
fi
