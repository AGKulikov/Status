#!/bin/bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  printf 'Usage: %s BASELINE_APK CLASSES19_DEX OUTPUT_ALIGNED_UNSIGNED_APK\n' "$0" >&2
  exit 2
fi

BASELINE_APK="$1"
CLASSES19_DEX="$2"
OUTPUT_APK="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APKTOOL_JAR="${APKTOOL_JAR:-}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || true)}"
EXPECTED_APKTOOL_SHA256="dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"

if [ ! -f "$BASELINE_APK" ] || [ ! -f "$CLASSES19_DEX" ]; then
  printf 'Baseline APK and classes19.dex must both exist\n' >&2
  exit 1
fi
if [ -z "$APKTOOL_JAR" ] || [ ! -f "$APKTOOL_JAR" ]; then
  printf 'Set APKTOOL_JAR to the reviewed apktool 3.0.3 jar\n' >&2
  exit 1
fi
if [ -z "$ZIPALIGN" ] || [ ! -x "$ZIPALIGN" ]; then
  printf 'Set ZIPALIGN to an executable Android SDK zipalign\n' >&2
  exit 1
fi

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [ "$(hash_file "$APKTOOL_JAR")" != "$EXPECTED_APKTOOL_SHA256" ]; then
  printf 'APKTOOL_JAR is not the reviewed apktool 3.0.3 binary\n' >&2
  exit 1
fi
if [ "$(hash_file "$BASELINE_APK")" != \
  '663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f' ]; then
  printf 'Input is not the exact working YN_30.3.0_T2 baseline\n' >&2
  exit 1
fi

BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/natro-navigation-apk.XXXXXX")"
trap 'rm -rf "$BUILD_ROOT"' EXIT HUP INT TERM
DECODED="$BUILD_ROOT/decoded"
DEX_PROJECT="$BUILD_ROOT/dex-project"

XDG_DATA_HOME="$BUILD_ROOT/apktool-data" \
  java -jar "$APKTOOL_JAR" d -f -r -o "$DECODED" "$BASELINE_APK"

# Window translucency is decided from the manifest Activity theme before lifecycle code runs.
# Patch only MapActivity's four-byte theme resource ID in the exact binary manifest. The existing
# translucent bootstrap style is already in the baseline; resources.arsc and every res/ payload
# therefore remain byte-for-byte original.
PATCHED_MANIFEST="$DECODED/AndroidManifest.xml"
python3 "$SCRIPT_DIR/patch_navigation_manifest_theme.py" "$PATCHED_MANIFEST"

mkdir -p "$DEX_PROJECT"
cp "$DECODED/apktool.yml" "$DEX_PROJECT/apktool.yml"
mv "$DECODED/smali_classes4" "$DEX_PROJECT/smali_classes4"
mv "$DECODED/smali_classes8" "$DEX_PROJECT/smali_classes8"
mv "$DECODED/smali_classes12" "$DEX_PROJECT/smali_classes12"
MAP_ACTIVITY="$DEX_PROJECT/smali_classes4/ru/yandex/yandexmaps/app/MapActivity.smali"
MAP_VIEW="$DEX_PROJECT/smali_classes12/com/yandex/mapkit/mapview/MapView.smali"
python3 "$SCRIPT_DIR/patch_navigation_map_activity.py" "$MAP_ACTIVITY"
python3 "$SCRIPT_DIR/patch_navigation_map_view.py" "$MAP_VIEW"
MANEUVER_VIEW="$DEX_PROJECT/smali_classes8/ru/yandex/yandexnavi/ui/guidance/maneuver/ContextManeuverView.smali"
python3 "$SCRIPT_DIR/patch_navigation_maneuver_view.py" "$MANEUVER_VIEW"

# The isolated project has no manifest/resources and contains only the three reviewed DEX lanes.
# Apktool therefore cannot rebuild any protected APK entry even accidentally.
XDG_DATA_HOME="$BUILD_ROOT/apktool-data" \
  java -jar "$APKTOOL_JAR" b --no-apk "$DEX_PROJECT"
CLASSES4_DEX="$DEX_PROJECT/build/apk/classes4.dex"
CLASSES12_DEX="$DEX_PROJECT/build/apk/classes12.dex"
CLASSES8_DEX="$DEX_PROJECT/build/apk/classes8.dex"
if [ ! -f "$CLASSES4_DEX" ] || [ ! -f "$CLASSES8_DEX" ] || [ ! -f "$CLASSES12_DEX" ]; then
  printf 'apktool did not produce all three reviewed DEX outputs\n' >&2
  exit 1
fi

UNALIGNED="$BUILD_ROOT/navigation-30.3.0-unaligned.apk"
python3 "$SCRIPT_DIR/repack_apk_entries.py" \
  --baseline "$BASELINE_APK" \
  --output "$UNALIGNED" \
  --replace "AndroidManifest.xml=$PATCHED_MANIFEST" \
  --replace "classes4.dex=$CLASSES4_DEX" \
  --replace "classes8.dex=$CLASSES8_DEX" \
  --replace "classes12.dex=$CLASSES12_DEX" \
  --replace "classes19.dex=$CLASSES19_DEX"

mkdir -p "$(dirname "$OUTPUT_APK")"
"$ZIPALIGN" -f -p 4 "$UNALIGNED" "$OUTPUT_APK"
python3 "$SCRIPT_DIR/verify_navigation_mod_baseline.py" \
  --baseline "$BASELINE_APK" \
  --candidate "$OUTPUT_APK" \
  --mode unsigned-patch

printf 'Aligned unsigned Navigator patch: %s\n' "$OUTPUT_APK"
printf 'It is not installable yet; sign it only with tools/sign_navigation_mod_30_3.sh.\n'
