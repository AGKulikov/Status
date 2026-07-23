#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 NAVIGATOR_APK STATUS_0X80_APK APKTOOL_JAR OUTPUT_APK" >&2
  echo "Optional signing env: APKSIGNER, ZIPALIGN, KEYSTORE_FILE, KEY_ALIAS, KEY_PASSWORD" >&2
  exit 2
}

[[ $# -eq 4 ]] || usage

navigator_apk="$(realpath "$1")"
status_apk="$(realpath "$2")"
apktool_jar="$(realpath "$3")"
output_apk="$(realpath -m "$4")"
script_dir="$(cd "$(dirname "$0")" && pwd)"

[[ -f "$navigator_apk" ]] || { echo "Navigator APK not found: $navigator_apk" >&2; exit 1; }
[[ -f "$status_apk" ]] || { echo "Status APK not found: $status_apk" >&2; exit 1; }
[[ -f "$apktool_jar" ]] || { echo "apktool jar not found: $apktool_jar" >&2; exit 1; }

expected_sha="a529f4f180ce42e29b1b7b7d801d21428ed8801d65271c46b9a29cb5769b4c3b"
actual_sha="$(sha256sum "$navigator_apk" | awk '{print $1}')"
[[ "$actual_sha" == "$expected_sha" ]] || {
  echo "Unsupported Navigator base: $actual_sha" >&2
  echo "Expected YN MonjaroMOD 29.4.2: $expected_sha" >&2
  exit 1
}

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

navigator_dir="$work_dir/navigator"
status_dir="$work_dir/status"
unsigned_apk="$work_dir/merged-unsigned.apk"
aligned_apk="$work_dir/merged-aligned.apk"
framework_dir="$work_dir/framework"
mkdir -p "$framework_dir"

java -jar "$apktool_jar" d -p "$framework_dir" -f --no-debug-info \
  -o "$navigator_dir" "$navigator_apk"
java -jar "$apktool_jar" d -p "$framework_dir" -f --no-debug-info \
  -o "$status_dir" "$status_apk"

status_public="$status_dir/res/values/public.xml"
[[ -f "$status_public" ]] || {
  echo "Decoded Status APK has no public.xml" >&2
  exit 1
}

python3 "$script_dir/merge_manifest.py" \
  --navigator "$navigator_dir/AndroidManifest.xml" \
  --status "$status_dir/AndroidManifest.xml" \
  --status-public "$status_public" \
  --output "$navigator_dir/AndroidManifest.xml"
python3 "$script_dir/merge_apktool_yml.py" \
  --navigator "$navigator_dir/apktool.yml" \
  --status "$status_dir/apktool.yml"
python3 "$script_dir/sanitize_decoded_navigator.py" "$navigator_dir"

next_dex=1
for directory in "$navigator_dir"/smali "$navigator_dir"/smali_classes*; do
  [[ -d "$directory" ]] || continue
  name="$(basename "$directory")"
  if [[ "$name" == "smali" ]]; then
    index=1
  else
    index="${name#smali_classes}"
  fi
  (( index >= next_dex )) && next_dex=$((index + 1))
done

for directory in "$status_dir"/smali "$status_dir"/smali_classes*; do
  [[ -d "$directory" ]] || continue
  target="$navigator_dir/smali_classes${next_dex}"
  cp -a "$directory" "$target"
  next_dex=$((next_dex + 1))
done

python3 "$script_dir/patch_navi_application.py" "$navigator_dir"

cp "$script_dir/status_widget_accessibility_service.xml" \
  "$navigator_dir/res/xml/status_widget_accessibility_service.xml"
cp "$script_dir/status_widget_file_paths.xml" \
  "$navigator_dir/res/xml/status_widget_file_paths.xml"
mkdir -p "$navigator_dir/assets"
cp "$status_apk" "$navigator_dir/assets/status_widget_resources.apk"

java -jar "$apktool_jar" b -p "$framework_dir" -o "$unsigned_apk" "$navigator_dir"

mkdir -p "$(dirname "$output_apk")"
artifact="$unsigned_apk"
if [[ -n "${ZIPALIGN:-}" ]]; then
  "$ZIPALIGN" -f -P 16 4 "$unsigned_apk" "$aligned_apk"
  artifact="$aligned_apk"
fi

if [[ -n "${APKSIGNER:-}" && -n "${KEYSTORE_FILE:-}" ]]; then
  : "${KEY_ALIAS:?KEY_ALIAS is required for signing}"
  : "${KEY_PASSWORD:?KEY_PASSWORD is required for signing}"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE_FILE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KEY_PASSWORD \
    --key-pass env:KEY_PASSWORD \
    --out "$output_apk" \
    "$artifact"
  "$APKSIGNER" verify --verbose --print-certs "$output_apk"
else
  cp "$artifact" "$output_apk"
  echo "Built unsigned merged APK (signing environment was not supplied)." >&2
fi

sha256sum "$output_apk" > "$output_apk.sha256"
echo "Merged APK: $output_apk"
