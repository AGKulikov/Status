#!/usr/bin/env bash
set -euo pipefail

EXPECTED_BASE_SHA256="16703a5594dcbd1ae96862e3a03bda39fc663cffd07e04955e1a9fd9eac7a52a"
EXPECTED_APKTOOL_SHA256="dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"
EXPECTED_PATCH_SHA256="e7c7a30e2b5c60e2c6fc19a4ca194fdab98cc9e687b9e1c9cd8860ef058e67ba"

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 BASE_2.1.2_APK APKTOOL_3.0.3_JAR OUTPUT_UNSIGNED_APK" >&2
  exit 64
fi

base_apk="$1"
apktool_jar="$2"
output_apk="$3"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$script_dir/natro-2.1.2-to-2.1.3.patch"

for command_name in java patch sha256sum mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 69
  }
done

[[ -f "$base_apk" ]] || { echo "Base APK not found" >&2; exit 66; }
[[ -f "$apktool_jar" ]] || { echo "Apktool JAR not found" >&2; exit 66; }
[[ ! -e "$output_apk" ]] || { echo "Refusing to overwrite output: $output_apk" >&2; exit 73; }

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

check_sha256 "$EXPECTED_BASE_SHA256" "$base_apk"
check_sha256 "$EXPECTED_APKTOOL_SHA256" "$apktool_jar"
check_sha256 "$EXPECTED_PATCH_SHA256" "$patch_file"

work_dir="$(mktemp -d /tmp/natro-2.1.3-recovery.XXXXXX)"
cleanup() {
  case "$work_dir" in
    /tmp/natro-2.1.3-recovery.*) rm -rf -- "$work_dir" ;;
  esac
}
trap cleanup EXIT

java -jar "$apktool_jar" d -f -p "$work_dir/framework" "$base_apk" -o "$work_dir/decoded"
patch --batch --fuzz=0 --directory="$work_dir/decoded" -p1 < "$patch_file"
java -jar "$apktool_jar" b -f -p "$work_dir/framework" "$work_dir/decoded" -o "$output_apk"

echo "Unsigned binary-recovery APK created: $output_apk"
sha256sum "$output_apk"
