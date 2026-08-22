#!/bin/sh
set -eu
export PYTHONDONTWRITEBYTECODE=1

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

python3 "$ROOT/verify_wire_protocol.py"
python3 "$ROOT/verify_enrollment_v1.py"
python3 "$ROOT/verify_role_switch_policy.py"
python3 "$ROOT/verify_helper_peripheral_route.py"
python3 "$ROOT/verify_helper_central_route.py"
python3 "$ROOT/verify_helper_telemetry.py"
python3 "$ROOT/verify_car_remote_v1.py"
python3 "$ROOT/verify_helper_switch_runtime.py"
python3 "$ROOT/verify_live_activity_v57.py"

PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"
[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 57;' "$PROJECT")" -eq 4 ]
[ "$(grep -Fc 'MARKETING_VERSION = 57.0;' "$PROJECT")" -eq 4 ]
[ "$(grep -Fc 'IPHONEOS_DEPLOYMENT_TARGET = 14.0;' "$PROJECT")" -eq 4 ]
[ "$(grep -Fc 'IPHONEOS_DEPLOYMENT_TARGET = 16.2;' "$PROJECT")" -eq 2 ]
! grep -Fq 'IPHONEOS_DEPLOYMENT_TARGET = 13.0;' "$PROJECT"
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper.liveactivity;' \
    "$PROJECT")" -eq 2 ]
for SOURCE in \
    AppDelegate.swift \
    SceneDelegate.swift \
    ViewController.swift \
    CarControlUI.swift \
    ANCSConnectionJournal.swift \
    NatroLiveActivityManager.swift \
    LiveActivitySettingsViewController.swift \
    NatroShortcuts.swift \
    BleRoleSwitchPolicy.swift \
    IphoneBleWireProtocolV2.swift \
    CarRemoteProtocolV1.swift \
    HelperEnrollmentV1.swift \
    HelperPeripheralRoute.swift \
    HelperCentralRoute.swift \
    HelperBleRuntimeCoordinator.swift \
    HelperSwitchRuntimeCoordinator.swift \
    HelperTelemetrySource.swift
do
    [ "$(grep -Fc "/* $SOURCE in Sources */" "$PROJECT")" -eq 2 ]
done
[ "$(grep -Fc '/* NatroLiveActivityShared.swift in Sources */' "$PROJECT")" -eq 4 ]
[ "$(grep -Fc '/* NatroLiveActivityWidget.swift in Sources */' "$PROJECT")" -eq 2 ]
grep -Fq 'bluetooth-central' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'bluetooth-peripheral' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'UIApplicationSceneManifest' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'UISceneDelegateClassName' "$ROOT/KX11ANCSHelper/Info.plist"
grep -Fq 'NSSupportsLiveActivities' "$ROOT/KX11ANCSHelper/Info.plist"
! grep -Fq 'telephony?.currentRadioAccessTechnology' "$ROOT/HelperTelemetrySource.swift"
! grep -Fq 'Timer.scheduledTimer(withTimeInterval: 1' "$ROOT/HelperTelemetrySource.swift"
grep -Fq 'public func requestFreshSample()' "$ROOT/HelperTelemetrySource.swift"
grep -Fq 'case telemetryRefresh = 0x52' "$ROOT/IphoneBleWireProtocolV2.swift"
grep -Fq 'let rootViewController = ViewController()' "$ROOT/KX11ANCSHelper/AppDelegate.swift"
grep -Fq '_ = rootViewController.view' "$ROOT/KX11ANCSHelper/AppDelegate.swift"
grep -Fq 'UIWindow(windowScene: windowScene)' "$ROOT/KX11ANCSHelper/SceneDelegate.swift"
! grep -Fq 'UIWindow(frame: UIScreen.main.bounds)' "$ROOT/KX11ANCSHelper/AppDelegate.swift"
grep -Fq '#if targetEnvironment(simulator)' "$ROOT/KX11ANCSHelper/ViewController.swift"
grep -Fq 'renderSimulatorUnavailable()' "$ROOT/KX11ANCSHelper/ViewController.swift"
grep -Fq 'Simulator intentionally does not start a BLE owner' "$ROOT/KX11ANCSHelper/ViewController.swift"
grep -Fq 'candidate, not a claim that reconnects are already field-stable' "$ROOT/README.md"
grep -Fq 'does **not** delete the system' "$ROOT/README.md"
grep -Fq 'KX11ANCSHelper.experimentalRouteB' "$ROOT/README.md"
grep -Fq 'waits for the GATT Service Changed indication' "$ROOT/README.md"
grep -Fq 'D2D9E4C4-47F1-4E44-A8BB-A932FD5AF200' "$ROOT/README.md"
grep -Fq 'source candidate; Apple-SDK CI and physical-device checks are release gates' \
    "$ROOT/RELEASE.txt"

echo 'PASS: Helper v57 preserves transport and fixes compact Live Activity, demo and controls'
