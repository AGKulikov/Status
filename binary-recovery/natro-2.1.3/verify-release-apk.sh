#!/usr/bin/env bash
set -euo pipefail

EXPECTED_APK_SHA256="a3b4906b8725814d83f6257400fb1b8925e938505cb8a5c5d5268c61450b0bb4"
EXPECTED_CLASSES2_SHA256="cb997dcccebf3894b27cbf8ed44115eae0b2aefbbe9020e5ebf36323cdc31d9a"
EXPECTED_CERT_SHA256="6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75"
EXPECTED_AAPT_SHA256="c076aeeee8bd3ce58395a093747202265ab80c6c12e59358f598a30a81fde13b"
EXPECTED_APKSIGNER_SHA256="b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0"
EXPECTED_ZIPALIGN_SHA256="c5f559e946de5a9e7d58792181db20383b228877812136bc469d97ae00a43b0a"

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 SIGNED_APK AAPT APKSIGNER ZIPALIGN" >&2
  exit 64
fi

apk="$1"
aapt="$2"
apksigner="$3"
zipalign="$4"

check_sha256() {
  local expected="$1"
  local file="$2"
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    echo "SHA-256 mismatch for $file" >&2
    exit 65
  }
}

check_sha256 "$EXPECTED_APK_SHA256" "$apk"
check_sha256 "$EXPECTED_AAPT_SHA256" "$aapt"
check_sha256 "$EXPECTED_APKSIGNER_SHA256" "$apksigner"
check_sha256 "$EXPECTED_ZIPALIGN_SHA256" "$zipalign"

badging="$($aapt dump badging "$apk")"
grep -Fq "package: name='ru.natro.statuswidget' versionCode='208021230' versionName='2.1.3'" <<<"$badging"
grep -Fq "sdkVersion:'28'" <<<"$badging"
grep -Fq "targetSdkVersion:'28'" <<<"$badging"
grep -Fq "application: label='Natro'" <<<"$badging"
if grep -Fq "application-debuggable" <<<"$badging"; then
  echo "Release APK is debuggable" >&2
  exit 65
fi

signature="$($apksigner verify --min-sdk-version 24 --verbose --print-certs "$apk")"
grep -Fq "Verified using v1 scheme (JAR signing): false" <<<"$signature"
grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$signature"
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$signature"
grep -Fq "Verified using v3.1 scheme (APK Signature Scheme v3.1): false" <<<"$signature"
grep -Fq "Verified using v4 scheme (APK Signature Scheme v4): false" <<<"$signature"
grep -Fq "Number of signers: 1" <<<"$signature"
grep -Fq "Signer #1 certificate SHA-256 digest: $EXPECTED_CERT_SHA256" <<<"$signature"

$zipalign -P 16 -c -v 4 "$apk" >/dev/null
unzip -t "$apk" >/dev/null

work_dir="$(mktemp -d /tmp/natro-2.1.3-verify.XXXXXX)"
cleanup() {
  case "$work_dir" in
    /tmp/natro-2.1.3-verify.*) rm -rf -- "$work_dir" ;;
  esac
}
trap cleanup EXIT
unzip -q "$apk" classes2.dex -d "$work_dir"
check_sha256 "$EXPECTED_CLASSES2_SHA256" "$work_dir/classes2.dex"

echo "PASS: exact Natro 2.1.3 signed release APK"
