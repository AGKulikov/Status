#!/bin/bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  printf 'Usage: %s HUD_SPEED_V76_APK CLASSES3_DEX OUTPUT_ALIGNED_UNSIGNED_APK\n' "$0" >&2
  exit 2
fi

BASELINE_APK="$1"
CLASSES3_DEX="$2"
OUTPUT_APK="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APKTOOL_JAR="${APKTOOL_JAR:-}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || true)}"
EXPECTED_APK_SHA256='9b8a4a4a636968e9b2ca92c8399cdaf18112e9519aec433a9ee7fe42adb413dd'
EXPECTED_APKTOOL_SHA256='dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'

hash_file() { sha256sum "$1" | awk '{print $1}'; }
if [ ! -f "$BASELINE_APK" ] || [ ! -f "$CLASSES3_DEX" ]; then
  printf 'HUD Speed baseline and classes3.dex must both exist\n' >&2
  exit 1
fi
if [ -z "$APKTOOL_JAR" ] || [ "$(hash_file "$APKTOOL_JAR")" != "$EXPECTED_APKTOOL_SHA256" ]; then
  printf 'APKTOOL_JAR must be the reviewed apktool 3.0.3 binary\n' >&2
  exit 1
fi
if [ -z "$ZIPALIGN" ] || [ ! -x "$ZIPALIGN" ]; then
  printf 'Set ZIPALIGN to an executable Android SDK zipalign\n' >&2
  exit 1
fi
if [ "$(hash_file "$BASELINE_APK")" != "$EXPECTED_APK_SHA256" ]; then
  printf 'Input is not the reviewed HUD Speed 76.0-L13 APK\n' >&2
  exit 1
fi

BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/natro-hud-speed-apk.XXXXXX")"
trap 'rm -rf "$BUILD_ROOT"' EXIT HUP INT TERM
DECODED="$BUILD_ROOT/decoded"
REBUILT="$BUILD_ROOT/rebuilt.apk"
PATCHED_MANIFEST="$BUILD_ROOT/AndroidManifest.xml"
UNALIGNED="$BUILD_ROOT/hud-speed-unaligned.apk"

XDG_DATA_HOME="$BUILD_ROOT/apktool-data" \
  java -jar "$APKTOOL_JAR" d -f -o "$DECODED" "$BASELINE_APK"
python3 "$SCRIPT_DIR/patch_hud_speed_bridge_manifest.py" "$DECODED/AndroidManifest.xml"
XDG_DATA_HOME="$BUILD_ROOT/apktool-data" \
  java -jar "$APKTOOL_JAR" b -o "$REBUILT" "$DECODED"
unzip -p "$REBUILT" AndroidManifest.xml > "$PATCHED_MANIFEST"

python3 "$SCRIPT_DIR/repack_apk_entries.py" \
  --baseline "$BASELINE_APK" \
  --output "$UNALIGNED" \
  --replace "AndroidManifest.xml=$PATCHED_MANIFEST" \
  --replace "classes3.dex=$CLASSES3_DEX"
# Drop obsolete v1 signatures from the temporary candidate. The v2/v3 signing block was already
# discarded by repack_apk_entries.py; a trusted signer is deliberately a separate final step.
zip -qd "$UNALIGNED" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF' \
  2>/dev/null || true
mkdir -p "$(dirname "$OUTPUT_APK")"
"$ZIPALIGN" -f -p 4 "$UNALIGNED" "$OUTPUT_APK"

printf 'Aligned unsigned HUD Speed bridge APK: %s\n' "$OUTPUT_APK"
printf 'This cannot update the installed HUD Speed until signed with that installation signer.\n'
