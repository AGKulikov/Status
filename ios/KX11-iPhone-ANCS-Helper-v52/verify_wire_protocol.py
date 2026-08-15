#!/usr/bin/env python3
"""Execute the shared Android/Swift ANCS v2 wire fixtures on Linux."""

from __future__ import annotations

import json
from pathlib import Path
import uuid


MODE = {"android_central": 1, "android_peripheral": 2}


def crc8_atm(data: bytes) -> int:
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def encode_control(kind: str, mode: int, payload: bytes, flags: int = 0) -> bytes:
    assert kind in "HCAR" and mode in (1, 2)
    assert len(payload) == 16 and any(payload)
    if kind == "H":
        assert flags & 0x02 and flags & ~0x03 == 0
    else:
        assert flags == 0
    return bytes((ord(kind), 2, mode, flags)) + payload


def decode_control(frame: bytes):
    if len(frame) != 20 or frame[0] not in map(ord, "HCAR") or frame[1] != 2:
        return None
    if frame[2] not in (1, 2) or not any(frame[4:]):
        return None
    flags = frame[3]
    if frame[0] == ord("H"):
        if flags & ~0x03 or not flags & 0x02:
            return None
    elif flags:
        return None
    return chr(frame[0]), frame[2], flags, frame[4:]


CHARGE = {"unknown": 0, "discharging": 1, "charging": 2, "full": 3}
NETWORK = {"unknown": 0, "offline": 1, "wifi": 2, "lte": 3, "5g": 4, "3g": 5, "2g": 6}


def encode_telemetry(vector: dict[str, object]) -> bytes:
    battery = vector["batteryPercent"]
    assert battery is None or 0 <= battery <= 100
    flags = (0 if battery is None else 1)
    flags |= 2 if vector["externalPower"] else 0
    flags |= 4 if vector["locked"] else 0
    flags |= CHARGE[vector["chargeState"]] << 3
    sequence = vector["sequence"]
    frame = bytes(
        (
            ord("T"),
            2,
            flags,
            0xFF if battery is None else battery,
            NETWORK[vector["network"]],
            sequence & 0xFF,
            sequence >> 8,
        )
    )
    return frame + bytes((crc8_atm(frame),))


def decode_telemetry(frame: bytes):
    if len(frame) != 8 or frame[:2] != b"T\x02" or frame[7] != crc8_atm(frame[:7]):
        return None
    flags = frame[2]
    if flags & ~0x1F:
        return None
    valid = bool(flags & 1)
    if (valid and frame[3] > 100) or (not valid and frame[3] != 0xFF):
        return None
    if frame[4] not in NETWORK.values():
        return None
    return {
        "batteryPercent": frame[3] if valid else None,
        "externalPower": bool(flags & 2),
        "chargeState": next(name for name, wire in CHARGE.items() if wire == (flags >> 3) & 3),
        "network": next(name for name, wire in NETWORK.items() if wire == frame[4]),
        "locked": bool(flags & 4),
        "sequence": frame[5] | frame[6] << 8,
    }


def verify_swift_surface() -> None:
    source = Path(__file__).with_name("IphoneBleWireProtocolV2.swift").read_text(encoding="utf-8")
    required = (
        "public enum IphoneBleWireProtocolV2",
        "controlFrameBytes = 20",
        "telemetryFrameBytes = 8",
        "case androidCentral = 1",
        "case androidPeripheral = 2",
        "case peerProof = 0x48",
        "case roleClose = 0x43",
        "case roleCloseAck = 0x41",
        "case telemetryRefresh = 0x52",
        "public static func encodeTelemetryRefresh",
        "public static func decodeControl",
        "public static func encodeTelemetry",
        "public static func decodeTelemetry",
        "public static func crc8",
    )
    for token in required:
        assert token in source, f"Swift wire surface is missing: {token}"
    imports = "\n".join(line for line in source.splitlines() if line.lstrip().startswith("import "))
    assert "CoreBluetooth" not in imports
    assert "localName" not in source


def verify_shared_fixtures() -> None:
    fixture_path = Path(__file__).resolve().parents[2] / "docs/ancs-v2-wire-vectors.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    assert fixture["protocolVersion"] == 2
    assert fixture["defaultAttPayloadBytes"] == 20
    assert {vector["type"] for vector in fixture["vectors"]} == {"H", "C", "A", "R", "T"}

    for vector in fixture["vectors"]:
        kind = vector["type"]
        expected = bytes.fromhex(vector["hex"])
        if kind == "H":
            installation = uuid.UUID(vector["installationId"]).bytes
            telemetry = "telemetry" in vector["name"]
            actual = encode_control(kind, MODE[vector["mode"]], installation, 0x02 | telemetry)
            decoded = decode_control(expected)
            assert decoded == (kind, MODE[vector["mode"]], 0x02 | telemetry, installation)
        elif kind in ("C", "A"):
            token = bytes.fromhex(vector["switchTokenHex"])
            actual = encode_control(kind, MODE[vector["targetMode"]], token)
            decoded = decode_control(expected)
            assert decoded == (kind, MODE[vector["targetMode"]], 0, token)
        elif kind == "R":
            token = bytes.fromhex(vector["requestTokenHex"])
            actual = encode_control(kind, MODE[vector["activeMode"]], token)
            decoded = decode_control(expected)
            assert decoded == (kind, MODE[vector["activeMode"]], 0, token)
        else:
            actual = encode_telemetry(vector)
            decoded = decode_telemetry(expected)
            for key in (
                "batteryPercent",
                "externalPower",
                "chargeState",
                "network",
                "locked",
                "sequence",
            ):
                assert decoded[key] == vector[key], f"{vector['name']}: decoded {key}"
        assert actual == expected, f"{vector['name']}: {actual.hex()} != {expected.hex()}"


def verify_fail_closed_decoders() -> None:
    valid = encode_control("H", 1, bytes(range(1, 17)), 3)
    mutations = (
        valid[:-1],
        valid[:1] + b"\x03" + valid[2:],
        valid[:2] + b"\x03" + valid[3:],
        valid[:3] + b"\x00" + valid[4:],
        valid[:3] + b"\x82" + valid[4:],
        valid[:4] + bytes(16),
    )
    assert all(decode_control(frame) is None for frame in mutations)
    close = bytearray(encode_control("C", 2, bytes(range(1, 17))))
    close[3] = 1
    assert decode_control(bytes(close)) is None
    refresh = bytearray(encode_control("R", 1, bytes(range(1, 17))))
    refresh[3] = 1
    assert decode_control(bytes(refresh)) is None

    telemetry = bytearray.fromhex("54021350033412b7")
    telemetry[7] ^= 1
    assert decode_telemetry(bytes(telemetry)) is None


def main() -> None:
    verify_swift_surface()
    verify_shared_fixtures()
    verify_fail_closed_decoders()
    print("PASS: all 5 shared ANCS v2 wire vectors + strict negative decode fixtures")


if __name__ == "__main__":
    main()
