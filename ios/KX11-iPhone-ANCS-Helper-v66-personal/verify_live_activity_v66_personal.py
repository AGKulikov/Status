#!/usr/bin/env python3
"""Portable audit for Helper 66 Personal Team local ActivityKit lifecycle and controls."""

from pathlib import Path
import hashlib
import json
import struct


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


project = text(ROOT / "KX11ANCSHelper.xcodeproj/project.pbxproj")
shared = text(ROOT / "NatroLiveActivityShared.swift")
manager = text(ROOT / "KX11ANCSHelper/NatroLiveActivityManager.swift")
settings = text(ROOT / "KX11ANCSHelper/LiveActivitySettingsViewController.swift")
shortcuts = text(ROOT / "KX11ANCSHelper/NatroShortcuts.swift")
dashboard = text(ROOT / "KX11ANCSHelper/CarControlUI.swift")
journal = text(ROOT / "KX11ANCSHelper/ANCSConnectionJournal.swift")
settings_root = text(ROOT / "KX11ANCSHelper/ViewController.swift")
scene = text(ROOT / "KX11ANCSHelper/SceneDelegate.swift")
runtime = text(ROOT / "HelperSwitchRuntimeCoordinator.swift")
widget = text(ROOT / "NatroLiveActivityExtension/NatroLiveActivityWidget.swift")
protocol = text(ROOT / "CarRemoteProtocolV1.swift")
registry = text(REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/"
                "CarRemoteControlRegistryV1.java")
passenger = text(REPO / "app/src/geely/java/dezz/status/widget/car/"
                 "GeelyPassengerControlIntegration.java")
controller = text(REPO / "app/src/main/java/dezz/status/widget/phone/"
                  "CarRemoteControllerV1.java")
phone = text(REPO / "app/src/main/java/dezz/status/widget/phone/"
             "PhoneConnectorController.java")
info = text(ROOT / "KX11ANCSHelper/Info.plist")

# One embedded WidgetKit target, one shared ActivityAttributes source and a real small resource.
assert 'name = NatroLiveActivityExtension;' in project
assert 'productType = "com.apple.product-type.app-extension";' in project
assert 'NatroLiveActivityExtension.appex in Embed App Extensions' in project
assert project.count('NatroLiveActivityShared.swift in Sources') == 4
assert project.count('Monjaro.png in Resources') == 4
assert 'IPHONEOS_DEPLOYMENT_TARGET = 16.2;' in project
assert '<key>NSSupportsLiveActivities</key>' in info

# Personal Team cannot provision App Groups. Commands must cross from the Live Activity intent to
# the already-running Bluetooth owner without opening the app.
for entitlement in (
    ROOT / "KX11ANCSHelper/KX11ANCSHelper.entitlements",
    ROOT / "NatroLiveActivityExtension/NatroLiveActivityExtension.entitlements",
):
    assert 'com.apple.security.application-groups' not in text(entitlement)
assert 'forSecurityApplicationGroupIdentifier: suiteName' in shared
assert 'FileManager.default.fileExists(atPath: container.path)' in shared
assert 'FileManager.default.isWritableFile(atPath: container.path)' in shared
assert 'return .standard' in shared
assert 'NatroLiveActivityCommandMailbox.enqueue(delivered)' in shared
assert 'CFNotificationCenterGetDarwinNotifyCenter()' in shared
assert 'consumePendingCommand()' in manager
assert 'actionQueueKey = "liveCommand.queue.v62"' in shared
assert 'Array(queue.suffix(16))' in shared
assert 'while let action = NatroLiveActivityCommandMailbox.consume()' in manager
assert 'static var supportsSharedDelivery: Bool' in shared
assert 'directNotificationPrefix' in shared
assert 'static var directNotificationNames: [CFString]' in shared
assert 'action(fromDirectNotification name: CFNotificationName?)' in shared
assert 'manager.receiveExternalCommand(directAction)' in manager
assert 'NatroLiveActivityCommandLink.url(' not in widget
assert widget.count('Button(intent: NatroLiveControlIntent') == 2
assert 'NatroLiveActivityCommandLink.action(from: context.url)' in scene
assert 'NatroLiveActivityManager.shared.receiveExternalCommand(delivered)' in scene
assert 'every fallback command requires an in-app confirmation' in scene

# Dynamic state is numeric and bounded. Large Helper-56 strings/catalog arrays are decode-only and
# cannot be emitted by encode(to:). Equivalent worst-case JSON stays far below 3.5/4 KB.
assert 'statusCode: UInt8' in shared
assert 'values: [Int32]' in shared and 'valueFlags: [UInt8]' in shared
assert 'Array(values.prefix(4))' in shared and 'Array(valueFlags.prefix(4))' in shared
assert 'case statusCode = "s"' in shared and 'values = "v"' in shared
encoder = shared[shared.index('func encode(to encoder: Encoder) throws'):
                 shared.index('var isDemo: Bool')]
for forbidden in ('activeControlIDs', 'availableControlIDs', 'controlSnapshots',
                  'vehicleName', 'controlIDs'):
    assert forbidden not in encoder
sample = json.dumps({"s": 4, "t": 30000, "c": 32767, "o": -32768,
                     "v": [2147483647] * 4, "f": [255] * 4,
                     "u": 4294967295}, separators=(",", ":")).encode()
assert len(sample) < 512
assert 'maximumEncodedStateBytes = 3_500' in manager
assert 'update blocked locally; stateBytes=' in manager
assert 'encodedSize(of: state) <= Self.maximumEncodedStateBytes' in manager
assert 'stableState.updatedAtEpoch = 0' in manager
assert 'lastActivityFingerprints[activity.id] != fingerprint' in manager
assert 'lastActivityFingerprints[activity.id] = fingerprint' in manager

# Two independent cards remain configurable, but each has four glanceable controls. Static
# attributes carry the car name/IDs/visibility and the upper card renders the bundled vehicle.
assert 'ActivityConfiguration(for: NatroLiveActivityAttributes.self)' in widget
assert 'DynamicIslandExpandedRegion(.bottom)' in widget
assert 'Button(intent: NatroLiveControlIntent' in widget
assert 'context.attributes.resolvedControls' in widget
assert 'ClimateActivityView(' in widget and 'FunctionsActivityView(' in widget
assert 'private struct StageIndicator: View' in widget
assert 'Image("Monjaro")' in widget
assert 'control.compactValue(snapshot?.value ?? 0, active: active)' in widget
assert 'func compactValue(_ value: Int32, active: Bool)' in shared
assert 'controlIDs: [UInt8]?' in shared and 'vehicleName: String?' in shared
assert 'showVehicle: Bool?' in shared
assert 'return Array(result.prefix(4))' in shared
assert 'count: 4' in shared
assert 'NatroLivePanel.allCases.filter' in manager
assert 'runningCount >= 2' in manager
assert 'replaceActivities(reason: "обновление Helper 66")' in manager
assert "minimumActivityUpdateInterval: TimeInterval = 0.5" in manager
assert "updateExistingActivities(immediate: true)" in manager
assert "pendingActivityStates" in manager
assert "flushUpdate" in manager
assert "CFBundleDisplayName" in info
assert "<string>Natro</string>" in info
assert "ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;" in project
assert ".font(.system(size: island ? 12 : 14, weight: .bold, design: .rounded))" in widget
assert ".lineLimit(functionGrid ? 2 : 1)" in widget
assert ".minimumScaleFactor(0.82)" in widget
assert ".font(.system(size: 12, weight: .bold, design: .rounded))" in widget

# Normal cards are owned by the authenticated C5 Bluetooth session, not by ANCS. A route with
# ANCS disabled must still accept car commands, while loss of C5 retires both cards immediately.
assert 'var isVehicleConnected: Bool { remote?.isSynced == true }' in manager
assert 'let connectedNow = remote.isSynced' in manager
assert 'guard isDemoMode || isVehicleConnected else' in manager
assert 'stop(reason: "магнитола отключена")' in manager
assert 'dismissalPolicy: .immediate' in manager
assert 'pendingStartAfterEnd' in manager
assert 'var vehicleConnected: Bool { (1...4).contains(statusCode) }' in shared
assert '.disabled(!state.vehicleConnected || !available)' in widget
assert '.disabled(!state.vehicleConnected || state.targetTemperatureHundredths == nil)' in widget
assert '.disabled(!state.ancsConnected' not in widget
assert '.fill(state.vehicleConnected ? Color.green : Color.gray)' in widget
assert 'полной C5-синхронизации с магнитолой' in settings

# Personal Team cannot carry aps-environment. Local ActivityKit start must not request a push token,
# and the app must not register the push-to-start observer or advertise a stale token to Natro.
assert 'pushToStartTokenUpdates' not in manager
assert 'Activity<NatroLiveActivityAttributes>.activityUpdates' in manager
assert 'pushType: nil' in manager
assert 'activity.pushTokenUpdates' not in manager
assert 'pushToStartDefaultsKey' not in manager
provision = manager[manager.index('private func provisionLocalState'):
                    manager.index('private func sendConfiguration')]
assert '.pushToStartToken' not in provision
assert 'observePushToken' not in provision
remote_change = manager[manager.index('func remoteDidChange'):
                        manager.index('/// Returns the exact effective state')]
assert 'liveActivityProviderReady' not in remote_change
assert 'scheduleLocalStartFallback' not in manager
assert 'liveActivityProviderReady: UInt8 = 0xfb' in protocol
assert 'LiveActivityPushProtocolV1' in protocol
assert 'AuthenticatedC5FrameV1.isValid' in text(ROOT / "HelperPeripheralRoute.swift")
assert 'AuthenticatedC5FrameV1.isValid' in text(ROOT / "HelperCentralRoute.swift")
assert '<key>aps-environment</key>' not in text(
    ROOT / "KX11ANCSHelper/KX11ANCSHelper.entitlements")

# Demo is one real mutable model for dashboard + both cards and never forges Shortcuts ANCS.
assert 'static var demoMode: Bool' in shared
assert 'statusCode: statusCode' in manager
assert 'let demo = isDemoMode' in manager
assert 'var isANCSConnected: Bool { remote?.ancsConnected == true }' in manager
assert 'applyDemo(controlID: controlID' in manager
assert 'demoValues[controlID] = 1' in manager
assert 'DispatchQueue.main.asyncAfter(deadline: .now() + 0.8)' in manager
assert 'Общий демо-режим' in settings
assert 'не подменяет реальный статус ANCS' in settings
assert 'manager.isDemoMode' in dashboard
assert 'VehicleControlSectionViewController' in dashboard
assert 'case .levels, .options:' in dashboard
assert 'case .range:' in dashboard
assert 'return state.active ? "Выполнено"' in dashboard

# The startup repair removes the persisted Helper-56 terminal dead end.
assert 'persisted.reducerPhase == .failed || persisted.reducerPhase == .closed' in runtime
assert 'терминальный снимок v56 восстановлен в основной route a' in runtime.lower()
assert 'legacy-настройка перенесена в основной route a' in runtime.lower()

# iOS Shortcuts receives actions/results, not a fictional third-party event trigger.
assert 'struct NatroANCSStatusIntent: AppIntent' in shortcuts
assert 'struct NatroWaitForANCSIntent: AppIntent' in shortcuts
assert 'struct NatroStartLiveActivityIntent: AppIntent' in shortcuts
assert 'struct NatroStopLiveActivityIntent: AppIntent' in shortcuts
assert 'NatroLiveActivityManager.shared.stop()' in shortcuts
assert 'static var openAppWhenRun = false' in shortcuts
assert 'ReturnsValue<Bool>' in shortcuts
assert 'Системный пользовательский триггер iOS создать нельзя' in settings

# C5 keeps the old required boundary and appends finite capability-gated Passenger controls.
assert 'cabinTemperature: UInt8 = 0xfc' in protocol
assert 'outdoorTemperature: UInt8 = 0xfd' in protocol
assert 'ancsConnected: UInt8 = 0xfe' in protocol
assert 'STATE_CABIN_TEMPERATURE = 0xfc' in controller
assert 'STATE_OUTDOOR_TEMPERATURE = 0xfd' in controller
assert 'STATE_ANCS_CONNECTED = 0xfe' in controller
assert 'STATE_LIVE_ACTIVITY_PROVIDER_READY = 0xfb' in controller
assert 'carRemote.setAncsReady(value);' in phone
assert 'optionalV56IDs' in protocol and 'optionalV57IDs' in protocol
assert 'requiredLegacyIDs' in protocol
for identifier in range(60, 66):
    assert f'add(wire, control, {identifier},' in registry
for marker in ('getPA_ReadLightFrontLeft', 'CB_ReadLightFrontLeft',
               'CB_ReadLightAllOnSwitch', 'getPA_Fragra_LvlReqSts',
               'CB_Fragra_LvlReq'):
    assert marker in passenger
for source in (shared, protocol, dashboard, widget, registry, passenger):
    assert 'massage' not in source.lower()
    assert 'массаж' not in source.lower()

# Real levels are visible/selectable; the journal is persistent, sequenced and exportable.
assert 'var isThreeStage: Bool' in shared and 'case .fragranceLevel:' in shared
assert 'level: snapshot?.level ?? 0' in widget
assert 'private let maximumLines = 1_600' in journal
assert r'[s=\(sessionID) #\(sequence)]' in journal
assert 'func exportURL() throws -> URL' in journal and 'private func sanitize' in journal
assert 'Показывать журнал' in settings_root
assert 'Экспортировать журнал' in settings_root
assert 'onDiagnosticEvent' in settings_root

# The vehicle asset is compact RGBA data, not a baked screenshot/checkerboard.
png = (ROOT / "NatroLiveActivityExtension/Monjaro.png").read_bytes()
assert hashlib.sha256(png).hexdigest() == (
    "e5aeab15b255195e62eb5d666f50a3ecee30aad58ba33b21d0132519fca9dda3"
)
assert png[:8] == b"\x89PNG\r\n\x1a\n" and png[12:16] == b"IHDR"
width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
    ">IIBBBBB", png[16:29]
)
assert (width, height) == (300, 157)
assert len(png) < 80_000
assert bit_depth == 8 and color_type == 6
assert compression == 0 and filtering == 0 and interlace in (0, 1)

print("PASS: Helper v66 Personal Team direct Live Activity controls and compact payload")
