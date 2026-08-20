#!/usr/bin/env python3
"""Portable v53 ECDH/SAS/wire vector and source-security contract."""

from __future__ import annotations

import hashlib
import hmac
import json
from pathlib import Path
from uuid import UUID


ROOT = Path(__file__).parent
DOMAIN = b"NATRO-F201-ENROLLMENT-V1"
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC
G = (
    0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
    0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5,
)


def point_add(left: tuple[int, int] | None, right: tuple[int, int] | None):
    if left is None:
        return right
    if right is None:
        return left
    x1, y1 = left
    x2, y2 = right
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    slope = (
        (3 * x1 * x1 + A) * pow(2 * y1, P - 2, P)
        if left == right
        else (y2 - y1) * pow(x2 - x1, P - 2, P)
    ) % P
    x3 = (slope * slope - x1 - x2) % P
    return x3, (slope * (x1 - x3) - y1) % P


def point_mul(scalar: int, point: tuple[int, int] = G):
    result = None
    while scalar:
        if scalar & 1:
            result = point_add(result, point)
        point = point_add(point, point)
        scalar >>= 1
    assert result is not None
    return result


def public_x963(scalar: int) -> bytes:
    x, y = point_mul(scalar)
    return b"\x04" + x.to_bytes(32, "big") + y.to_bytes(32, "big")


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    output = b""
    block = b""
    counter = 1
    while len(output) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        output += block
        counter += 1
    return output[:length]


def auth(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def sas(master: bytes, transcript: bytes) -> tuple[str, int]:
    modulus = 100_000_000
    maximum_uint64 = (1 << 64) - 1
    discarded_tail = (maximum_uint64 % modulus + 1) % modulus
    maximum_accepted = maximum_uint64 - discarded_tail
    for counter in range(1 << 32):
        value = int.from_bytes(
            auth(master, transcript + b"sas" + counter.to_bytes(4, "big"))[:8],
            "big",
        )
        if value <= maximum_accepted:
            return f"{value % modulus:08d}", counter
    raise AssertionError("unreachable SAS rejection exhaustion")


def hx(data: bytes) -> str:
    return data.hex()


def verify_vector() -> None:
    vector = json.loads((ROOT / "enrollment_v1_vectors.json").read_text())
    assert vector["schema"] == vector["domain_utf8"] == DOMAIN.decode()
    assert vector["required_att_mtu"] == 103
    android_id = UUID(vector["android_installation_uuid"]).bytes
    helper_id = UUID(vector["helper_installation_uuid"]).bytes
    android_private = int(vector["android_private_scalar_hex"], 16)
    helper_private = int(vector["helper_private_scalar_hex"], 16)
    android_public = public_x963(android_private)
    helper_public = public_x963(helper_private)
    assert hx(android_public) == vector["android_public_x963_hex"]
    assert hx(helper_public) == vector["helper_public_x963_hex"]
    shared = point_mul(android_private, point_mul(helper_private))[0].to_bytes(32, "big")
    assert hx(shared) == vector["ecdh_shared_secret_x_hex"]

    android_nonce = bytes.fromhex(vector["android_nonce_hex"])
    helper_nonce = bytes.fromhex(vector["helper_nonce_hex"])
    hello = b"\x01\x01" + android_id + android_nonce + android_public
    response = b"\x01\x81" + helper_id + helper_nonce + helper_public
    assert len(hello) == len(response) == 99
    assert hx(hello) == vector["enrollment_hello_hex"]
    assert hx(response) == vector["enrollment_response_hex"]
    transcript = DOMAIN + hello + response
    transcript_hash = hashlib.sha256(transcript).digest()
    assert hx(transcript_hash) == vector["transcript_sha256_hex"]
    master = hkdf(shared, transcript_hash, b"session-master")
    long_term = hkdf(master, transcript_hash, b"long-term")
    assert hx(master) == vector["session_master_hex"]
    assert hx(long_term) == vector["long_term_key_hex"]
    code, counter = sas(master, transcript)
    assert code == vector["sas_8_digits"] and counter == vector["sas_counter"]

    confirm_core = b"\x01\x03" + android_id + android_nonce
    confirm = confirm_core + auth(master, transcript + confirm_core + b"android-confirm")
    assert len(confirm) == 66 and hx(confirm) == vector["enrollment_confirm_hex"]
    waiting_core = b"\x01\x80" + helper_id + helper_nonce
    waiting = waiting_core + auth(
        master, transcript + confirm + waiting_core + b"helper-waiting-sas"
    )
    pre_auth_core = b"\x01\x83" + helper_id + helper_nonce
    pre_auth_ack = pre_auth_core + auth(
        master, transcript + confirm + pre_auth_core + b"helper-ack"
    )
    assert len(waiting) == 66 and hx(waiting) == vector["enrollment_waiting_sas_hex"]
    assert len(pre_auth_ack) == 66 and hx(pre_auth_ack) == vector["enrollment_pre_auth_ack_hex"]
    commit_core = b"\x01\x05" + android_id + android_nonce
    commit = commit_core + auth(master, transcript + confirm + commit_core + b"android-commit")
    assert len(commit) == 66 and hx(commit) == vector["enrollment_commit_hex"]
    transaction_id = hashlib.sha256(transcript + confirm + commit).digest()
    assert hx(transaction_id) == vector["transaction_id_hex"]
    ack_core = b"\x01\x85" + helper_id + helper_nonce
    ack = ack_core + auth(
        long_term, DOMAIN + transaction_id + ack_core + b"helper-commit-ack"
    )
    assert hx(ack) == vector["enrollment_commit_ack_hex"]

    routine_android_nonce = bytes.fromhex(vector["routine_android_nonce_hex"])
    routine_helper_nonce = bytes.fromhex(vector["routine_helper_nonce_hex"])
    routine_hello = b"\x01\x02" + android_id + routine_android_nonce
    routine_core = b"\x01\x82" + helper_id + routine_helper_nonce
    routine_proof = routine_core + auth(
        long_term, DOMAIN + routine_hello + routine_core + b"routine-proof"
    )
    routine_confirm_core = b"\x01\x04" + android_id + routine_android_nonce
    routine_confirm = routine_confirm_core + auth(
        long_term,
        DOMAIN + routine_hello + routine_proof + routine_confirm_core
        + b"android-routine-confirm",
    )
    routine_ack_core = b"\x01\x84" + helper_id + routine_helper_nonce
    routine_ack = routine_ack_core + auth(
        long_term,
        DOMAIN + routine_hello + routine_proof + routine_confirm
        + routine_ack_core + b"helper-ack",
    )
    assert hx(routine_hello) == vector["routine_hello_hex"]
    assert hx(routine_proof) == vector["routine_proof_hex"]
    assert hx(routine_confirm) == vector["routine_confirm_hex"]
    assert hx(routine_ack) == vector["routine_ack_hex"]
    assert not hmac.compare_digest(routine_ack[-32:], b"\x00" * 32)


def verify_source_contract() -> None:
    protocol = (ROOT / "HelperEnrollmentV1.swift").read_text()
    route = (ROOT / "HelperPeripheralRoute.swift").read_text()
    owner = (ROOT / "HelperBleRuntimeCoordinator.swift").read_text()
    runtime = (ROOT / "HelperSwitchRuntimeCoordinator.swift").read_text()
    ui = (ROOT / "KX11ANCSHelper/ViewController.swift").read_text()

    assert 'transcriptDomain = Data("NATRO-F201-ENROLLMENT-V1".utf8)' in protocol
    assert "P256.KeyAgreement.PrivateKey" in protocol and "x963Representation" in protocol
    assert "hkdfDerivedSymmetricKey" in protocol and "HMAC<SHA256>" in protocol
    assert "unbiasedSASNumber" in protocol and "counter.bigEndian" in protocol
    assert "kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly" in protocol
    assert "stagePending" in protocol and "promotePending" in protocol
    assert "transactionID" in protocol and "pending: PendingBinding?" in protocol
    assert "kSecReturnData" in protocol
    assert "legacyDefaults.string(forKey:" not in protocol
    assert "legacyDefaults.removeObject(forKey: legacyDefaultsKey)" in protocol
    assert "print(" not in protocol and "NSLog" not in protocol

    assert 'enrollmentUUID = CBUUID(string: "D2D9E4C4-47F1-4E44-A8BB-A932FD5AF200")' in route
    assert "properties: [.read, .write]" in route
    assert "permissions: [.readable, .writeable]" in route
    assert route.count("permissions: [.readEncryptionRequired]") >= 2
    assert "permissions: [.writeEncryptionRequired]" in route
    assert "maximumEnrollmentHelloAttempts" in route
    assert "minimumEnrollmentHelloInterval" in route
    assert "deadlineUptimeNanoseconds" in route and "DispatchTime.now().uptimeNanoseconds" in route
    assert "validateEnrollmentConfirm" in route and "validateEnrollmentCommit" in route
    assert "makeEnrollmentWaitingSAS" in route
    assert "didReadAuthenticatedC4Ack" in route
    assert "isAuthenticatedPreauthAck" in route
    assert "request.offset == 0" in route and "!peerProof.isEmpty" in route
    assert route.index("peripheral.respond(to: request, withResult: .success)\n            preauthenticated.didReadEncryptedH = true") > 0
    assert "pendingEnrollmentBinding" in route and "promotePending" in route
    routine_confirm = route[route.index("private func handleRoutineConfirm"):route.index("private func clearEnrollmentSession")]
    assert "promotePending" not in routine_confirm
    h_read = route[route.index("if request.characteristic.uuid == HelperPeripheralRoute.peerProofUUID"):route.index("let value: Data?")]
    assert h_read.index("preauthenticated.didReadAuthenticatedC4Ack") < h_read.index("promotePending")
    assert h_read.index("promotePending") < h_read.index("request.value = peerProof")
    assert h_read.index("request.value = peerProof") < h_read.index("preauthenticated.didReadEncryptedH = true")
    final_commit = route[route.index("private func handleEnrollmentCommit"):route.index("private func handleRoutineConfirm")]
    assert final_commit.index("preauthenticated.didReadAuthenticatedC4Ack") < final_commit.index("preauthenticated.didReadEncryptedH")
    assert final_commit.index("preauthenticated.didReadEncryptedH") < final_commit.index("stagePending(")
    control_write = route[route.index("didReceiveWrite requests:"):route.index("didSubscribeTo characteristic:")]
    assert control_write.index("didReadAuthenticatedC4Ack") < control_write.index("didReadEncryptedH")
    assert control_write.index("didReadEncryptedH") < control_write.index("permitsControl")
    assert "control subscription arrived before authenticated C4 proof" in route
    assert "preauthenticated.didReadEncryptedH" in route
    assert "preauthenticated.permitsControl" in route
    assert "CBAdvertisementDataLocalNameKey" not in route
    assert "CBAdvertisementDataManufacturerDataKey" not in route
    c4_handlers = route[route.index("private func handleEnrollmentWrite"):route.index("private func clearEnrollmentSession")]
    assert ".insufficientAuthorization" not in c4_handlers

    assert "beginRouteAEnrollment" in owner and "resetRouteAEnrollmentBinding" in owner
    assert "beginEnrollment()" in runtime and "confirmEnrollmentSAS()" in runtime
    assert 'title.text = "Настройки Helper"' in ui
    assert 'setTitle("Коды совпадают"' in ui
    assert "didEnterBackgroundNotification" in ui
    assert 'title: "Сбросить связь Natro?"' in ui
    assert "UIAlertController" in ui and "resetEnrollmentBinding" in ui
    assert "sasLabel.text = code" in ui
    assert "print(" not in ui and "NSLog" not in ui


if __name__ == "__main__":
    verify_vector()
    verify_source_contract()
    print("PASS: v53 preserves ECDH/SAS, dual-key WAL recovery, C4 gates and shared vectors")
