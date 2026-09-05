#!/usr/bin/env python3
"""Portable source/asset audit for Helper 55 Live Activity, demo and ANCS actions."""

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
widget = text(ROOT / "NatroLiveActivityExtension/NatroLiveActivityWidget.swift")
protocol = text(ROOT / "CarRemoteProtocolV1.swift")
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
assert 'NatroLiveActivityCommandMailbox.enqueue(action)' in shared
assert 'CFNotificationCenterGetDarwinNotifyCenter()' in shared
assert 'consumePendingCommand()' in manager

# Lock Screen + Dynamic Island remain configurable and interactive on iOS 17+.
assert 'ActivityConfiguration(for: NatroLiveActivityAttributes.self)' in widget
assert 'DynamicIslandExpandedRegion(.bottom)' in widget
assert 'Button(intent: NatroLiveControlIntent' in widget
assert 'state.controls' in widget
assert 'Image("Monjaro")' in widget
assert 'frame(height: 62)' in widget and 'frame(height: 43)' in widget

# Demo is explicit and cannot forge the real Shortcuts ANCS boolean.
assert 'static var demoMode: Bool' in shared
assert 'status: "ДЕМО · ANCS подключён"' in manager
assert 'isDemo: true' in manager
assert 'var isANCSConnected: Bool { remote?.ancsConnected == true }' in manager
assert 'Демо без автомобиля' in settings
assert 'реальный ANCS не подменяется' in settings

# iOS 26 Shortcuts receives actions/results, not a fictional third-party event trigger.
assert 'struct NatroANCSStatusIntent: AppIntent' in shortcuts
assert 'struct NatroWaitForANCSIntent: AppIntent' in shortcuts
assert 'ReturnsValue<Bool>' in shortcuts
assert 'iOS 26 не даёт приложениям добавлять собственный системный триггер' in settings

# C5 keeps the 39-command catalog frozen and carries three read-only companion STATE ids.
assert 'cabinTemperature: UInt8 = 0xfc' in protocol
assert 'outdoorTemperature: UInt8 = 0xfd' in protocol
assert 'ancsConnected: UInt8 = 0xfe' in protocol
assert 'STATE_CABIN_TEMPERATURE = 0xfc' in controller
assert 'STATE_OUTDOOR_TEMPERATURE = 0xfd' in controller
assert 'STATE_ANCS_CONNECTED = 0xfe' in controller
assert 'car.subscribeTelemetry' in controller
assert 'car.unsubscribeTelemetry' in controller
assert 'carRemote.setAncsReady(value);' in phone

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

print("PASS: Helper v55 Live Activity, real-alpha car, demo and ANCS Shortcuts actions")
