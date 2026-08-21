#!/usr/bin/env python3
"""Portable source/asset audit for Helper 56 Live Activity, demo and ANCS actions."""

from pathlib import Path
import hashlib
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
widget = text(ROOT / "NatroLiveActivityExtension/NatroLiveActivityWidget.swift")
protocol = text(ROOT / "CarRemoteProtocolV1.swift")
registry = text(REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/CarRemoteControlRegistryV1.java")
controller = text(REPO / "app/src/main/java/dezz/status/widget/phone/CarRemoteControllerV1.java")
phone = text(REPO / "app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java")
info = text(ROOT / "KX11ANCSHelper/Info.plist")

# One embedded WidgetKit target, one shared ActivityAttributes source and a real resource.
assert 'name = NatroLiveActivityExtension;' in project
assert 'productType = "com.apple.product-type.app-extension";' in project
assert 'NatroLiveActivityExtension.appex in Embed App Extensions' in project
assert project.count('NatroLiveActivityShared.swift in Sources') == 4
assert 'Monjaro.png in Resources' in project
assert 'IPHONEOS_DEPLOYMENT_TARGET = 16.2;' in project
assert '<key>NSSupportsLiveActivities</key>' in info

# Extension and app share only a tiny command mailbox; the BLE owner stays in the app.
for entitlement in [
    ROOT / "KX11ANCSHelper/KX11ANCSHelper.entitlements",
    ROOT / "NatroLiveActivityExtension/NatroLiveActivityExtension.entitlements",
]:
    assert 'group.ru.natro.kx11ancshelper' in text(entitlement)
assert 'NatroLiveActivityCommandMailbox.enqueue(delivered)' in shared
assert 'CFNotificationCenterGetDarwinNotifyCenter()' in shared
assert 'consumePendingCommand()' in manager

# Two independent Lock Screen panels + Dynamic Island remain configurable and interactive.
assert 'ActivityConfiguration(for: NatroLiveActivityAttributes.self)' in widget
assert 'DynamicIslandExpandedRegion(.bottom)' in widget
assert 'Button(intent: NatroLiveControlIntent' in widget
assert 'state.controls' in widget
assert 'FunctionsActivityView(state: state)' in widget
assert 'state.resolvedFunctionControls' in widget
assert 'private struct StageIndicator: View' in widget
assert 'Image("Monjaro")' in widget
assert 'frame(height: 61)' in widget and 'frame(height: 43)' in widget
assert 'enum NatroLivePanel: String' in shared
assert 'case climate' in shared and 'case functions' in shared
assert 'count: 10' in shared and 'functionControls' in shared
assert 'NatroLivePanel.allCases.filter' in manager
assert 'for panel in missing' in manager
assert 'runningCount >= 2' in manager

# Demo is explicit and cannot forge the real Shortcuts ANCS boolean.
assert 'static var demoMode: Bool' in shared
assert 'status = "ДЕМО · ANCS подключён"' in manager
assert 'isDemo: demo' in manager
assert 'var isANCSConnected: Bool { remote?.ancsConnected == true }' in manager
assert 'Общий демо-режим' in settings
assert 'не подменяет реальный статус ANCS' in settings
assert 'manager.isDemoMode' in dashboard

# iOS 26 Shortcuts receives actions/results, not a fictional third-party event trigger.
assert 'struct NatroANCSStatusIntent: AppIntent' in shortcuts
assert 'struct NatroWaitForANCSIntent: AppIntent' in shortcuts
assert 'ReturnsValue<Bool>' in shortcuts
assert 'Системный пользовательский триггер iOS создать нельзя' in settings

# C5 preserves the legacy 39-ID baseline, accepts optional v56 capabilities and carries state IDs.
assert 'cabinTemperature: UInt8 = 0xfc' in protocol
assert 'outdoorTemperature: UInt8 = 0xfd' in protocol
assert 'ancsConnected: UInt8 = 0xfe' in protocol
assert 'STATE_CABIN_TEMPERATURE = 0xfc' in controller
assert 'STATE_OUTDOOR_TEMPERATURE = 0xfd' in controller
assert 'STATE_ANCS_CONNECTED = 0xfe' in controller
assert 'car.subscribeTelemetry' in controller
assert 'car.unsubscribeTelemetry' in controller
assert 'carRemote.setAncsReady(value);' in phone
assert 'requiredLegacyIDs' in protocol
assert 'optionalV56IDs' in protocol
for marker in ('options(25, "Подогрев сзади слева"', 'ambientThemes',
               'Закрыть окно водителя'):
    assert marker in protocol
for marker in ('"climate.seat_heat_rear_left"', '"comfort.ambient_theme"',
               '"vehicle.window_close_driver"'):
    assert marker in registry

# Three-stage heat/ventilation is visible, one-shot actions send ACTIVATE=1, and massage is absent.
assert 'var isThreeStage: Bool' in shared
assert 'level: snapshot?.level ?? 0' in widget
assert 'value: operation == .activate ? 1 : 0' in manager
for source in (shared, protocol, dashboard, widget, registry):
    assert 'massage' not in source.lower()
    assert 'массаж' not in source.lower()

# The privacy-filtered connection journal is persistent, optional in Settings and exportable.
assert 'private let maximumLines = 800' in journal
assert 'func exportURL() throws -> URL' in journal
assert 'private func sanitize' in journal
assert 'Показывать журнал' in settings_root
assert 'Экспортировать журнал' in settings_root
assert 'onDiagnosticEvent' in settings_root
assert 'ANCSConnectionJournal.shared.append' in settings_root

# The generated vehicle asset must be actual RGBA, not a baked checkerboard.
png = (ROOT / "NatroLiveActivityExtension/Monjaro.png").read_bytes()
assert hashlib.sha256(png).hexdigest() == (
    "90be75050f60aa4229c14c8fff62a8068757d53c314ac0f9495fbe2b2e214c5a"
)
assert png[:8] == b"\x89PNG\r\n\x1a\n"
assert png[12:16] == b"IHDR"
width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
    ">IIBBBBB", png[16:29]
)
assert width <= 1200 and height <= 800 and width >= 600 and height >= 300
assert bit_depth == 8 and color_type == 6
assert compression == 0 and filtering == 0 and interlace in (0, 1)

print("PASS: Helper v56 dual Live Activity, levels, demo, journals and ANCS actions")
