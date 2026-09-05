#!/bin/bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  printf 'Usage: %s HUD_SPEED_V76_APK BUILD_TOOLS_DIR OUTPUT_SIGNED_APK\n' "$0" >&2
  exit 2
fi

BASELINE_APK="$1"
BUILD_TOOLS_DIR="$2"
OUTPUT_APK="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXPECTED_CERT_SHA256='6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'
KEYSTORE_FILE="${KEYSTORE_FILE:-}"
KEY_ALIAS="${KEY_ALIAS:-status-widget-ha}"
APKTOOL_JAR="${APKTOOL_JAR:-}"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
AAPT="$BUILD_TOOLS_DIR/aapt"

if [ -e "$OUTPUT_APK" ]; then
  printf 'Refusing to overwrite existing output: %s\n' "$OUTPUT_APK" >&2
  exit 1
fi
if [ -z "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ]; then
  printf 'ANDROID_SDK_ROOT or ANDROID_HOME must point at an Android SDK\n' >&2
  exit 1
fi
if [ -z "$KEYSTORE_FILE" ] || [ ! -f "$KEYSTORE_FILE" ]; then
  printf 'Set KEYSTORE_FILE to the stable Natro update keystore\n' >&2
  exit 1
fi
if [ -z "${KEY_PASSWORD:-}" ]; then
  printf 'Set KEY_PASSWORD in the environment; it is never accepted as an argument\n' >&2
  exit 1
fi
for required in "$BASELINE_APK" "$APKTOOL_JAR"; do
  if [ ! -f "$required" ]; then
    printf 'Required input is missing: %s\n' "$required" >&2
    exit 1
  fi
done
for tool in "$APKSIGNER" "$ZIPALIGN" "$AAPT"; do
  if [ ! -x "$tool" ]; then
    printf 'Android build tool is missing or not executable: %s\n' "$tool" >&2
    exit 1
  fi
done

export KEY_PASSWORD
KEYTOOL_OUTPUT=$(keytool -J-Duser.language=en -J-Duser.country=US \
  -list -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
  -storepass:env KEY_PASSWORD)
KEYSTORE_CERT=$(printf '%s\n' "$KEYTOOL_OUTPUT" \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
  | tr -d ':\r' | tr 'A-F' 'a-f' | head -n 1)
if [ "$KEYSTORE_CERT" != "$EXPECTED_CERT_SHA256" ]; then
  printf 'Keystore certificate is not the stable Natro update certificate\n' >&2
  exit 1
fi

WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/natro-hud-speed-sign.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT HUP INT TERM
UNSIGNED_APK="$WORK_ROOT/HUD-Speed-NatroBridge-unsigned.apk"

bash "$SCRIPT_DIR/build_hud_speed_bridge_dex.sh" "$WORK_ROOT/dex"
APKTOOL_JAR="$APKTOOL_JAR" ZIPALIGN="$ZIPALIGN" \
  bash "$SCRIPT_DIR/build_hud_speed_bridge_apk.sh" \
    "$BASELINE_APK" "$WORK_ROOT/dex/classes3.dex" "$UNSIGNED_APK"

mkdir -p "$(dirname "$OUTPUT_APK")"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEY_PASSWORD \
  --key-pass env:KEY_PASSWORD \
  --min-sdk-version 23 \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT_APK" \
  "$UNSIGNED_APK"

VERIFY_V3=$("$APKSIGNER" verify --verbose --print-certs \
  --min-sdk-version 28 "$OUTPUT_APK")
VERIFY_V2=$("$APKSIGNER" verify --verbose --print-certs \
  --min-sdk-version 24 "$OUTPUT_APK")
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$VERIFY_V3"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$VERIFY_V2"
grep -Fq 'Number of signers: 1' <<<"$VERIFY_V3"
grep -Fq "Signer #1 certificate SHA-256 digest: ${EXPECTED_CERT_SHA256}" <<<"$VERIFY_V3"
"$ZIPALIGN" -c -P 16 -v 4 "$OUTPUT_APK" >/dev/null
BADGING=$("$AAPT" dump badging "$OUTPUT_APK")
grep -Fq \
  "package: name='air.StrelkaHUDFREE' versionCode='76000013' versionName='76.0-L13'" \
  <<<"$BADGING"

sha256sum "$OUTPUT_APK"
printf '%s\n' \
  'The supplied original HUD Speed certificate is unavailable. This Natro-signed build' \
  'requires uninstalling a differently signed HUD Speed before installation.'
