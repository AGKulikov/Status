#!/usr/bin/env python3
"""Portable C5 protocol, security-gate and UI contract verifier."""

from __future__ import annotations

import json
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
VECTORS = REPO / "docs/car-remote-v1-vectors.json"


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = (((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)) & 0xFFFF
    return crc


def encode(vector: dict[str, int | str]) -> bytes:
    payload = struct.pack(
        "<6BHIiH",
        0x4E,
        1,
        int(vector["type"]),
        int(vector["control_id"]),
        int(vector["code"]),
        int(vector["flags"]),
        int(vector["transaction_id"]),
        int(vector["sequence"]),
        int(vector["value"]),
        int(vector["max_age_deciseconds"]),
    )
    return payload + struct.pack("<H", crc16(payload))


def strict_decode(frame: bytes) -> tuple[int, ...] | None:
    if len(frame) != 20 or frame[0:2] != b"N\x01":
        return None
    if crc16(frame[:18]) != struct.unpack_from("<H", frame, 18)[0]:
        return None
    magic, version, kind, control, code, flags, tx, sequence, value, age = struct.unpack(
        "<6BHIiH", frame[:18]
    )
    if sequence == 0 or kind not in range(1, 7):
        return None
    if kind in (1, 6) and (control or code or flags or tx or value or age):
        return None
    if kind == 2 and (not control or code not in range(1, 6) or flags & ~0x39 or tx or value or age):
        return None
    if kind == 3 and (not control or code or flags & ~0x1F or tx or age):
        return None
    if kind == 4 and (not control or code not in range(1, 5) or flags & ~0x40
                      or not tx or age not in range(1, 51)):
        return None
    if kind == 5 and (not control or code not in range(0, 7)
                      or flags != (0 if code == 0 else 0x80) or not tx or age):
        return None
    return magic, version, kind, control, code, flags, tx, sequence, value, age


def verify_vectors() -> None:
    fixture = json.loads(VECTORS.read_text(encoding="utf-8"))
    assert fixture["schema"] == "natro-car-remote-v1"
    assert fixture["frame_bytes"] == 20
    for vector in fixture["vectors"]:
        encoded = encode(vector)
        assert encoded.hex() == vector["hex"], vector["name"]
        decoded = strict_decode(encoded)
        assert decoded is not None and decoded[8] == vector["value"], vector["name"]

        torn = bytearray(encoded)
        torn[12] ^= 0x01
        assert strict_decode(bytes(torn)) is None
        assert strict_decode(encoded[:-1]) is None

    # Exact high-order ECARX values survive; a float32 representation would lose low bits.
    drive = next(item for item in fixture["vectors"] if item["name"] == "command_dynamic_drive_mode")
    assert strict_decode(bytes.fromhex(drive["hex"]))[8] == 0x22010103


def verify_sources() -> None:
    swift = (ROOT / "CarRemoteProtocolV1.swift").read_text(encoding="utf-8")
    peripheral = (ROOT / "HelperPeripheralRoute.swift").read_text(encoding="utf-8")
    central = (ROOT / "HelperCentralRoute.swift").read_text(encoding="utf-8")
    switch = (ROOT / "HelperSwitchRuntimeCoordinator.swift").read_text(encoding="utf-8")
    ui = (ROOT / "KX11ANCSHelper/CarControlUI.swift").read_text(encoding="utf-8")
    settings = (ROOT / "KX11ANCSHelper/ViewController.swift").read_text(encoding="utf-8")
    java_protocol = (REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/"
                     "IphoneCarRemoteProtocolV1.java").read_text(encoding="utf-8")
    registry = (REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/"
                "CarRemoteControlRegistryV1.java").read_text(encoding="utf-8")
    controller = (REPO / "app/src/main/java/dezz/status/widget/phone/"
                  "CarRemoteControllerV1.java").read_text(encoding="utf-8")
    protected_queue = (REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/"
                       "CarRemoteFrameQueueV1.java").read_text(encoding="utf-8")
    route_a = (REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
               "AndroidCentralTransportV2.java").read_text(encoding="utf-8")
    route_b = (REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
               "AndroidPeripheralTransportV2.java").read_text(encoding="utf-8")

    uuid = "D2D9E4C5-47F1-4E44-A8BB-A932FD5AF200"
    assert uuid in peripheral and uuid in central
    assert "d2d9e4c5-47f1-4e44-a8bb-a932fd5af200" in (
        REPO / "app/src/main/java/dezz/status/widget/phone/transport/v2/"
        "IphoneBleProtocolV2.java"
    ).read_text(encoding="utf-8").lower()
    assert "FRAME_BYTES = 20" in java_protocol and "frameBytes = 20" in swift
    assert "buffer.putInt(frame.value)" in java_protocol
    assert "UInt32(bitPattern: frame.value)" in swift
    assert "CRC-16/CCITT-FALSE" in java_protocol and "crc16(bytes[0..<18])" in swift

    # C5 is a separate encrypted characteristic; the occupied C2 role protocol is unchanged.
    assert "permissions: [.writeEncryptionRequired]" in peripheral
    assert "properties: [.write, .indicate]" in peripheral
    assert "request.central.identifier == controlSubscriberID" in peripheral
    assert "preauthenticated.permitsControl" in peripheral
    assert "AuthenticatedC5FrameV1.isValid(value)" in peripheral
    assert "case .subscribingCarRemote" in central
    assert "carRemote.properties.contains(.indicate)" in central
    assert "case .active(let activeGeneration) = lifecycle" in central
    assert "self.policy.state.phase == .active" in switch and "self.peerReady" in switch

    assert "PERMISSION_WRITE_ENCRYPTED" in route_b
    assert "PROPERTY_WRITE" in route_b and "PROPERTY_INDICATE" in route_b
    assert "state.isReady()" in route_b and "ownsCarRemoteDescriptor" in route_b
    assert "SUBSCRIBE_CAR_REMOTE" in route_a and "WRITE_TYPE_DEFAULT" in route_a
    assert "carRemoteSubscriptionToken.sameOwner(owner.ownerToken)" in route_a

    # A finite registry is the only path to vehicle functions; C5 never carries a raw function id.
    assert registry.count("add(wire, control,") >= 30
    assert '30, "vehicle.trunk", true, true, false' in registry
    assert "forWireId(frame.controlId)" in controller
    assert "entry.requiresConfirmation" in controller and "FLAG_CONFIRMED" in controller
    assert "MAX_COMMANDS_PER_SECOND" in controller and "isNewerSequence" in controller
    assert "commandValueMatches" in controller and "frame.value > 100 * entry.scale" in controller
    assert "CarControlCommand(entry.controlId" in controller
    assert "functionId" not in java_protocol and "functionId" not in swift

    # A session is complete only when every registry entry arrived. Live state may coalesce, but
    # catalog/result/sync frames are protected from the old remove-first queue loss.
    assert "CarControlDescriptor.Kind.ACTION.ordinal() + 1" in controller
    assert "HELLO_COALESCE_MS = 1_500L" in controller
    assert "descriptor == null ? 0" not in controller
    assert "expectedCatalogIDs" in swift and "catalogTailSeen && missing.isEmpty" in swift
    assert "withTimeInterval: 2" in swift
    assert "removeOldestState" in protected_queue and "HARD_LIMIT = 192" in protected_queue
    assert "removeFirst()" not in route_a and "removeFirst()" not in route_b

    # Vehicle control is now one Live-Activity-style dashboard. Legacy Bluetooth/SAS management
    # stays in Settings and the catalog itself owns the five user-facing sections.
    for section in ("Климат", "Сиденья", "Автомобиль", "Комфорт", "Медиа"):
        assert section in swift
    assert 'title: "Автомобиль"' in ui and 'title: "Настройки"' in ui
    for scene_case in ("case coolDown", "winterMorning", "comfort", "allOff"):
        assert scene_case in ui
    assert "sendSceneCommand" in swift and "pendingResults" in swift
    assert "selectableValues(for: definition)" in ui
    assert "private func makeLevelStrip" in ui and "for index in 1...3" in ui
    assert "final class ViewController: UITabBarController" in ui
    assert "final class HelperSettingsViewController" in settings
    assert "beginEnrollment" in settings and "confirmEnrollmentSAS" in settings
    assert "setTransportReady(snapshot.phase == .active)" in settings
    assert "http://" not in swift + peripheral + central + ui
    assert "https://" not in swift + peripheral + central + ui


def main() -> None:
    verify_vectors()
    verify_sources()
    print("PASS: C5 exact Int32 vectors, finite registry, encrypted owner gates, UI tabs and scenes")


if __name__ == "__main__":
    main()
