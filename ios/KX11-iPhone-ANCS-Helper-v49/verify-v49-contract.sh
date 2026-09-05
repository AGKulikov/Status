#!/bin/sh
set -eu
export PYTHONDONTWRITEBYTECODE=1

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$ROOT/../.." && pwd)

python3 "$ROOT/verify_wire_protocol.py"
python3 "$ROOT/verify_role_switch_policy.py"
python3 "$ROOT/verify_helper_peripheral_route.py"
python3 "$ROOT/verify_helper_central_route.py"
python3 "$ROOT/verify_helper_telemetry.py"
python3 "$ROOT/verify_helper_switch_runtime.py"

PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"
[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 49;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 49.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]
for SOURCE in \
    AppDelegate.swift \
    ViewController.swift \
    BleRoleSwitchPolicy.swift \
    IphoneBleWireProtocolV2.swift \
    HelperPeripheralRoute.swift \
    HelperCentralRoute.swift \
    HelperBleRuntimeCoordinator.swift \
    HelperSwitchRuntimeCoordinator.swift \
    HelperTelemetrySource.swift
do
    # One file reference, one group member, one build-file declaration, and one Sources entry.
    [ "$(grep -Fc "$SOURCE" "$PROJECT")" -eq 4 ]
done
grep -Fq 'bluetooth-central' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'bluetooth-peripheral' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'candidate, not a claim that reconnects are already field-stable' "$ROOT/README.md"
grep -Fq 'does **not** delete the system' "$ROOT/README.md"
grep -Fq 'KX11ANCSHelper.experimentalRouteB' "$ROOT/README.md"
grep -Fq 'waits for the GATT Service Changed indication' "$ROOT/README.md"
grep -Fq 'Status: source/replay accepted; hosted Xcode build and physical-device matrix remain release gates' \
    "$ROOT/RELEASE.txt"
grep -Fq 'ios/KX11-iPhone-ANCS-Helper-v49/**' \
    "$REPO_ROOT/.github/workflows/verify-helper-v49.yml"

echo 'PASS: Helper v49 clean-room source, shared vectors, restoration drains, and app contract'
