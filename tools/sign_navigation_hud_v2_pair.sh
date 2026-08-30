#!/bin/bash
set -euo pipefail

if [ "$#" -ne 4 ]; then
  printf 'Usage: %s BASELINE_NAVIGATOR_APK CANDIDATE_DIR BUILD_TOOLS_DIR OUTPUT_DIR\n' \
    "$0" >&2
  exit 2
fi

BASELINE_APK="$1"
CANDIDATE_DIR="$2"
BUILD_TOOLS_DIR="$3"
OUTPUT_DIR="$4"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXPECTED_CERT_SHA256='6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'
EXPECTED_BASELINE_SHA256='663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f'
EXPECTED_NATRO_VERSION_NAME="${EXPECTED_NATRO_VERSION_NAME:-2.5.7}"
EXPECTED_NATRO_VERSION_CODE="${EXPECTED_NATRO_VERSION_CODE:-208021290}"
KEYSTORE_FILE="${KEYSTORE_FILE:-}"
KEY_ALIAS="${KEY_ALIAS:-status-widget-ha}"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
AAPT="$BUILD_TOOLS_DIR/aapt"
APKTOOL_JAR="$CANDIDATE_DIR/apktool_3.0.3.jar"
CLASSES19_DEX="$CANDIDATE_DIR/classes19.dex"
MANIFEST="$CANDIDATE_DIR/candidate.json"

for command in jq keytool python3 sha256sum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Required release command is missing: %s\n' "$command" >&2
    exit 1
  fi
done

for required in "$BASELINE_APK" "$APKTOOL_JAR" "$CLASSES19_DEX" "$MANIFEST" \
    "$CANDIDATE_DIR/SHA256SUMS.txt"; do
  if [ ! -f "$required" ]; then
    printf 'Required release input is missing: %s\n' "$required" >&2
    exit 1
  fi
done
for tool in "$APKSIGNER" "$ZIPALIGN" "$AAPT"; do
  if [ ! -x "$tool" ]; then
    printf 'Android build tool is missing or not executable: %s\n' "$tool" >&2
    exit 1
  fi
done
if [ -z "$KEYSTORE_FILE" ] || [ ! -f "$KEYSTORE_FILE" ]; then
  printf 'Set KEYSTORE_FILE to the stable Natro update keystore\n' >&2
  exit 1
fi
if [ -z "${KEY_PASSWORD:-}" ]; then
  printf 'Set KEY_PASSWORD in the environment; it is never accepted as an argument\n' >&2
  exit 1
fi

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'
  fi
}

test "$(hash_file "$BASELINE_APK")" = "$EXPECTED_BASELINE_SHA256" || {
  printf 'Navigator input is not the exact reviewed 30.3.0 baseline\n' >&2
  exit 1
}
(
  cd "$CANDIDATE_DIR"
  sha256sum -c SHA256SUMS.txt
)

VERSION_NAME=$(jq -er '.natro.versionName' "$MANIFEST")
VERSION_CODE=$(jq -er '.natro.versionCode' "$MANIFEST")
test "$(jq -er '.schema' "$MANIFEST")" = 'natro-navigation-hud-v2-candidate/v1'
test "$(jq -er '.repository' "$MANIFEST")" = 'AGKulikov/Status'
test "$VERSION_NAME" = "$EXPECTED_NATRO_VERSION_NAME"
test "$VERSION_CODE" = "$EXPECTED_NATRO_VERSION_CODE"
test "$(jq -er '.navigator.baselineSha256' "$MANIFEST")" = \
  "$EXPECTED_BASELINE_SHA256"
test "$(jq -er '.toolchain.apktoolSha256' "$MANIFEST")" = \
  'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'
test "$(jq -er '.toolchain.androidBuildTools' "$MANIFEST")" = '36.0.0'
SOURCE_REPOSITORY=$(jq -er '.repository' "$MANIFEST")
SOURCE_SHA=$(jq -er '.sourceSha' "$MANIFEST")
SOURCE_TREE=$(jq -er '.sourceTree' "$MANIFEST")
NATRO_UNSIGNED="$CANDIDATE_DIR/Natro-${VERSION_NAME}-unsigned-release.apk"
test -f "$NATRO_UNSIGNED"

export KEY_PASSWORD
KEYTOOL_OUTPUT=$(keytool -J-Duser.language=en -J-Duser.country=US \
  -list -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
  -storepass:env KEY_PASSWORD)
KEYSTORE_CERT=$(printf '%s\n' "$KEYTOOL_OUTPUT" \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
  | tr -d ':\r' | tr 'A-F' 'a-f' | head -n 1)
test "$KEYSTORE_CERT" = "$EXPECTED_CERT_SHA256" || {
  printf 'Keystore certificate is not the stable Natro update certificate\n' >&2
  exit 1
}

mkdir -p "$OUTPUT_DIR"
NATRO_SIGNED="$OUTPUT_DIR/Natro-${VERSION_NAME}-signed.apk"
NAVIGATOR_SIGNED="$OUTPUT_DIR/YN_30.3.0_Natro-HUD-v2-signed.apk"
REPORT="$OUTPUT_DIR/signature-reports.txt"
RELEASE_JSON="$OUTPUT_DIR/release-report.json"
COMPATIBILITY_REPORT="$OUTPUT_DIR/KX11-COMPATIBILITY.txt"
SUMS="$OUTPUT_DIR/SHA256SUMS.txt"
for output in "$NATRO_SIGNED" "$NAVIGATOR_SIGNED" "$REPORT" "$RELEASE_JSON" \
    "$COMPATIBILITY_REPORT" "$SUMS"; do
  if [ -e "$output" ]; then
    printf 'Refusing to overwrite existing release output: %s\n' "$output" >&2
    exit 1
  fi
done

WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/natro-navigation-pair.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT HUP INT TERM
NAVIGATOR_UNSIGNED="$WORK_ROOT/YN_30.3.0_Natro-HUD-v2-unsigned.apk"

APKTOOL_JAR="$APKTOOL_JAR" ZIPALIGN="$ZIPALIGN" \
  "$SCRIPT_DIR/build_navigation_mod_30_3.sh" \
    "$BASELINE_APK" "$CLASSES19_DEX" "$NAVIGATOR_UNSIGNED"
KEYSTORE_FILE="$KEYSTORE_FILE" KEY_ALIAS="$KEY_ALIAS" KEY_PASSWORD="$KEY_PASSWORD" \
  APKSIGNER="$APKSIGNER" \
  "$SCRIPT_DIR/sign_navigation_mod_30_3.sh" \
    "$BASELINE_APK" "$NAVIGATOR_UNSIGNED" "$NAVIGATOR_SIGNED"

if "$APKSIGNER" verify "$NATRO_UNSIGNED" >/dev/null 2>&1; then
  printf 'Natro input unexpectedly contains a valid signature\n' >&2
  exit 1
fi
"$ZIPALIGN" -c -P 16 -v 4 "$NATRO_UNSIGNED" >/dev/null
"$APKSIGNER" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEY_PASSWORD \
  --key-pass env:KEY_PASSWORD \
  --min-sdk-version 28 \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$NATRO_SIGNED" \
  "$NATRO_UNSIGNED"

NATRO_VERIFY=$("$APKSIGNER" verify --verbose --print-certs \
  --min-sdk-version 28 "$NATRO_SIGNED")
NATRO_V2_VERIFY=$("$APKSIGNER" verify --verbose --print-certs \
  --min-sdk-version 24 "$NATRO_SIGNED")
NAVIGATOR_VERIFY=$("$APKSIGNER" verify --verbose --print-certs \
  --min-sdk-version 28 "$NAVIGATOR_SIGNED")
for verification in "$NATRO_VERIFY" "$NAVIGATOR_VERIFY"; do
  grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' \
    <<<"$verification"
  grep -Fq 'Number of signers: 1' <<<"$verification"
  grep -Fq \
    "Signer #1 certificate SHA-256 digest: ${EXPECTED_CERT_SHA256}" \
    <<<"$verification"
done
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  <<<"$NATRO_V2_VERIFY"
"$ZIPALIGN" -c -P 16 -v 4 "$NATRO_SIGNED" >/dev/null
"$ZIPALIGN" -c -P 16 -v 4 "$NAVIGATOR_SIGNED" >/dev/null

BADGING=$("$AAPT" dump badging "$NATRO_SIGNED")
grep -Fq \
  "package: name='ru.natro.statuswidget' versionCode='${VERSION_CODE}' versionName='${VERSION_NAME}'" \
  <<<"$BADGING"
grep -Fxq "application-label:'Natro'" <<<"$BADGING"

EXPECTED_NATRO_VERSION_NAME="$EXPECTED_NATRO_VERSION_NAME" \
EXPECTED_NATRO_VERSION_CODE="$EXPECTED_NATRO_VERSION_CODE" \
python3 "$SCRIPT_DIR/verify_kx11_navigation_pair.py" \
  --baseline "$BASELINE_APK" \
  --navigator "$NAVIGATOR_SIGNED" \
  --natro "$NATRO_SIGNED" \
  --aapt "$AAPT" \
  --apksigner "$APKSIGNER" \
  --zipalign "$ZIPALIGN" \
  --report "$COMPATIBILITY_REPORT"

{
  printf '%s\n' 'Natro:'
  printf '%s\n' "$NATRO_VERIFY"
  printf '\n%s\n' 'Natro v2-presence probe (API 24 verifier mode):'
  printf '%s\n' "$NATRO_V2_VERIFY"
  printf '\n%s\n' 'Navigator:'
  printf '%s\n' "$NAVIGATOR_VERIFY"
} > "$REPORT"

NATRO_SHA256=$(hash_file "$NATRO_SIGNED")
NAVIGATOR_SHA256=$(hash_file "$NAVIGATOR_SIGNED")
jq -n \
  --arg schema 'natro-navigation-hud-v2-release/v1' \
  --arg versionName "$VERSION_NAME" \
  --argjson versionCode "$VERSION_CODE" \
  --arg certificateSha256 "$EXPECTED_CERT_SHA256" \
  --arg natroApk "$(basename "$NATRO_SIGNED")" \
  --arg natroSha256 "$NATRO_SHA256" \
  --arg navigatorApk "$(basename "$NAVIGATOR_SIGNED")" \
  --arg navigatorSha256 "$NAVIGATOR_SHA256" \
  --arg navigatorBaselineSha256 "$EXPECTED_BASELINE_SHA256" \
  --arg sourceRepository "$SOURCE_REPOSITORY" \
  --arg sourceSha "$SOURCE_SHA" \
  --arg sourceTree "$SOURCE_TREE" \
  '{schema: $schema, source: {repository: $sourceRepository,
      commit: $sourceSha, tree: $sourceTree}, certificateSha256: $certificateSha256,
    natro: {versionName: $versionName, versionCode: $versionCode,
      apk: $natroApk, sha256: $natroSha256, signatureSchemes: ["v2", "v3"]},
    navigator: {versionName: "30.3.0", package: "ru.yandex.yandexnavi",
      apk: $navigatorApk, sha256: $navigatorSha256,
      baselineSha256: $navigatorBaselineSha256, signatureSchemes: ["v3"],
      changedEntries: ["AndroidManifest.xml", "classes4.dex", "classes12.dex"],
      newEntries: ["classes19.dex"]},
    compatibility: {headUnit: "ECARX KX11", androidApi: 28,
      mainContent: {width: 1760, height: 720}, navigatorAbi: "arm64-v8a",
      hud: {displayId: 2, surfaceWidth: 1920, surfaceHeight: 1080,
        x: 0, y: 720, width: 728, height: 190}, status: "static-gates-passed"}}' \
  > "$RELEASE_JSON"
(
  cd "$OUTPUT_DIR"
  sha256sum "$(basename "$NATRO_SIGNED")" "$(basename "$NAVIGATOR_SIGNED")" \
    "$(basename "$REPORT")" "$(basename "$RELEASE_JSON")" \
    "$(basename "$COMPATIBILITY_REPORT")" > "$(basename "$SUMS")"
  sha256sum -c "$(basename "$SUMS")"
)
printf 'Signed Natro and Navigator pair: %s\n' "$OUTPUT_DIR"
