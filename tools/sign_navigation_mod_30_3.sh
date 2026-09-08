#!/bin/bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  printf 'Usage: %s BASELINE_APK ALIGNED_UNSIGNED_APK OUTPUT_SIGNED_APK\n' "$0" >&2
  exit 2
fi

BASELINE_APK="$1"
UNSIGNED_APK="$2"
OUTPUT_APK="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
KEYSTORE_FILE="${KEYSTORE_FILE:-}"
KEY_ALIAS="${KEY_ALIAS:-status-widget-ha}"

if [ -z "$APKSIGNER" ] || [ ! -x "$APKSIGNER" ]; then
  printf 'Set APKSIGNER to an executable Android SDK apksigner\n' >&2
  exit 1
fi
if [ -z "$KEYSTORE_FILE" ] || [ ! -f "$KEYSTORE_FILE" ]; then
  printf 'Set KEYSTORE_FILE to the protected Natro release keystore\n' >&2
  exit 1
fi
if [ -z "${KEY_PASSWORD:-}" ]; then
  printf 'Set KEY_PASSWORD in the environment; it is never accepted as a command argument\n' >&2
  exit 1
fi
if [ ! -f "$UNSIGNED_APK" ] || [ "$UNSIGNED_APK" = "$OUTPUT_APK" ]; then
  printf 'Unsigned input must exist and output must be a different path\n' >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_APK")"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEY_PASSWORD \
  --key-pass env:KEY_PASSWORD \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT_APK" \
  "$UNSIGNED_APK"

python3 "$SCRIPT_DIR/verify_navigation_mod_baseline.py" \
  --baseline "$BASELINE_APK" \
  --candidate "$OUTPUT_APK" \
  --mode release \
  --apksigner "$APKSIGNER"
# Keep v3 for Android 9 and provide v2 for the APK's declared Android 8 minimum.
# Check each platform range explicitly; a v3 result alone does not verify v2.
"$APKSIGNER" verify --verbose --min-sdk-version 26 --max-sdk-version 27 \
  "$OUTPUT_APK" >/dev/null
"$APKSIGNER" verify --verbose --min-sdk-version 28 --max-sdk-version 28 \
  "$OUTPUT_APK" >/dev/null
printf 'Signed and verified Navigator 30.3.0: %s\n' "$OUTPUT_APK"
