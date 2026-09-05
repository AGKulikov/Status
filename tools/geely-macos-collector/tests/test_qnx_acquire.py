"""Offline wire fixtures; no network endpoint or real adb invocation."""

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

LIB = Path(__file__).resolve().parents[1] / "lib"
sys.path.insert(0, str(LIB))
import qnx_acquire as acquire_module
import qconn_native as qconn


REMOTE = "/usr/bin/missing evidence"


def target(path=REMOTE, limit=65536):
    return {"id": "QNX-TEST", "path": path, "max_bytes": limit,
            "reason": "Offline fixture for one missing ordinary binary"}


class WireFixture:
    """Default dialect frames from the pinned client; deterministic fake peer."""

    def __init__(self, payload=b"\x7fELF\x00\xff\r\nfixture\x00", open_error=None,
                 mode=0x81ED, short=False, close_error=False, timeout=False,
                 mutate=False, broken_disconnect=False):
        self.payload = payload
        self.open_error = open_error
        self.mode = mode
        self.short = short
        self.close_error = close_error
        self.timeout = timeout
        self.mutate = mutate
        self.broken_disconnect = broken_disconnect
        self.incoming = bytearray(b"QCONN\r\n\xff\xfd\x22")
        self.frames = []
        self.closed = False
        self.stat_count = 0
        self.default_timeout = 15
        self.abort_check = None

    def write(self, payload):
        self.frames.append(payload)
        if payload == b"info\r\n":
            self.incoming.extend(b"OS=nto SYSNAME=QNX RELEASE=7.0.X QCONN_VERSION=1.4.207944\r\n")
        elif payload == b"service file\r\n":
            self.incoming.extend(b"OK\r\n")
        elif payload.startswith(b'o:"'):
            self.incoming.extend(self.open_error or b"o:7\r\n")
        elif payload == b"s:7\r\n":
            self.stat_count += 1
            size = len(self.payload) + (1 if self.mutate and self.stat_count > 1 else 0)
            values = [1, size, 2, 0, 0, 0, 100, 100, 100, self.mode, 1, 4096, 1, 4096, 1]
            self.incoming.extend(("o:" + ":".join(format(value, "x") for value in values) + "\r\n").encode())
        elif payload.startswith(b"r:7:"):
            if self.timeout:
                raise qconn.QconnAbortError("fixture read timed out")
            fields = payload.rstrip().split(b":")
            offset, count = int(fields[2], 16), int(fields[3], 16)
            data = self.payload[offset:offset + count]
            if self.short:
                data = b"" if offset else self.payload[:2]
            self.incoming.extend(("o:%x\r\n" % len(data)).encode() + data)
        elif payload == b"c:7\r\n":
            self.incoming.extend(b"e:close failed\r\n" if self.close_error else b"o\r\n")
        elif payload == b"q":
            if self.broken_disconnect:
                raise qconn.QconnError("disconnect transport failed")
        else:
            raise AssertionError("Unexpected wire frame " + repr(payload))

    def read_exact(self, length, timeout=None):
        if len(self.incoming) < length:
            raise qconn.QconnError("fixture stream ended or timed out")
        result = bytes(self.incoming[:length])
        del self.incoming[:length]
        return result

    def read_until(self, delimiter, maximum=qconn.MAX_LINE_BYTES, timeout=None):
        index = self.incoming.find(delimiter)
        if index < 0:
            raise qconn.QconnError("fixture stream ended or timed out")
        return self.read_exact(index + len(delimiter))

    def close(self):
        self.closed = True


class AcquireTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="QNX collection with spaces ")
        self.destination = Path(self.temp.name) / "new evidence"

    def tearDown(self):
        self.temp.cleanup()

    def acquire(self, fixture, targets=None, known=None):
        with patch.object(acquire_module, "_open_stream", return_value=fixture) as transport:
            result = acquire_module.acquire("/Applications/Android Tools/adb", "fixture-serial",
                                            targets or [target()], self.destination, known or set())
        return result, transport

    def assert_clean(self):
        self.assertFalse(list(Path(self.temp.name).rglob("*.partial")))

    def test_native_source_is_byte_identical_pinned_sha(self):
        self.assertEqual(hashlib.sha256((LIB / "qconn_native.py").read_bytes()).hexdigest(),
                         "9cf3dfade347adadbea436bb78fa1374a34d84d31189f931a2587e48236364c7")

    def test_proven_default_wire_binary_bytes_hash_and_cleanup(self):
        fixture = WireFixture()
        result, _ = self.acquire(fixture)
        receipt = result[0]
        self.assertEqual(receipt["status"], "collected")
        self.assertTrue(receipt["ok"])
        self.assertEqual(Path(receipt["local_path"]).read_bytes(), fixture.payload)
        self.assertEqual(receipt["sha256"], hashlib.sha256(fixture.payload).hexdigest())
        self.assertEqual(receipt["bytes"], len(fixture.payload))
        self.assertEqual(fixture.frames[0:3], [b"info\r\n", b"service file\r\n", b'o:"/usr/bin/missing evidence":0\r\n'])
        self.assertIn(b"r:7:0:10:0\r\n", fixture.frames)
        self.assertEqual(fixture.frames[-2:], [b"c:7\r\n", b"q"])
        self.assertTrue(fixture.closed)
        self.assert_clean()

    def test_adb_transport_argv_is_fixed_and_uses_no_shell(self):
        fake_process = Mock()
        with patch.object(acquire_module.subprocess, "Popen", return_value=fake_process) as popen:
            with patch.object(acquire_module, "BoundedProcessByteStream") as stream_class:
                acquire_module._open_stream("/a directory/adb", "serial with spaces", 4, lambda: None)
        args, kwargs = popen.call_args
        self.assertEqual(args[0], ["/a directory/adb", "-s", "serial with spaces", "shell", "-T", "toybox", "nc", "-w", "30", "198.18.34.2", "8000"])
        self.assertFalse(kwargs["shell"])
        self.assertTrue(kwargs["start_new_session"])

    def test_dangerous_paths_rejected_before_transport(self):
        paths = ["/dev/can0", "/device/can", "/proc/net/can/reset_stats", "/proc/123/as",
                 "/proc/boot/../net/can", "/proc/boot/dir/file", "/etc/shadow", "/etc/ssh/ssh_host_key",
                 "/etc/config.token", "/etc/credentials.json", "/etc/tokens.conf", "/etc/secrets.ini",
                 "/data/keys.bin", "/usr/bin/a\nservice launcher", "/usr/bin/*",
                 '/usr/bin/a"', "/usr/bin/./hello", "/usr//bin/hello", "/usr/bin/.ssh/key"]
        result, transport = self.acquire(WireFixture(), [target(path) for path in paths])
        self.assertEqual(len(result), len(paths))
        self.assertTrue(all(receipt["status"] == "rejected" for receipt in result))
        transport.assert_not_called()
        self.assert_clean()

    def test_exact_boot_file_allowed_device_type_still_rejected(self):
        fixture = WireFixture(mode=0x21FF)
        result, _ = self.acquire(fixture, [target("/proc/boot/a_library.so")])
        self.assertEqual(result[0]["status"], "error")
        self.assertFalse(any(frame.startswith(b"r:") for frame in fixture.frames))
        self.assertEqual(fixture.frames[-2:], [b"c:7\r\n", b"q"])
        self.assert_clean()

    def test_only_exact_evidenced_lv_image_exception_is_allowed(self):
        self.assertEqual(acquire_module.validate_target(target("/vm/images_lv/linux-lv.img")),
                         "/vm/images_lv/linux-lv.img")
        for path in ("/vm/images_lv/another.img", "/vm/images/linux-lv.img",
                     "/vm/images_lv/linux-lv.img/child", "/vm/images_lv/../images_lv/linux-lv.img",
                     "/vm/images_lv/linux-lv.img.bak", "/vm/images_lv/linux-lv.img*", "/vm/"):
            with self.subTest(path=path), self.assertRaises(acquire_module.RejectedTarget):
                acquire_module.validate_target(target(path))
        for mode in (0xA1FF, 0x21B6, 0x41ED):
            with self.subTest(mode=mode):
                fixture = WireFixture(mode=mode)
                receipts, _ = self.acquire(fixture, [target("/vm/images_lv/linux-lv.img")])
                self.assertEqual(receipts[0]["status"], "error")
                self.assertFalse(any(frame.startswith(b"r:") for frame in fixture.frames))
                self.assert_clean()

    def test_missing_permission_opaque_error_are_distinct(self):
        for response, expected in [(b"e:ENOENT\r\n", "missing"),
                                   (b"e:Permission denied\r\n", "permission_denied"),
                                   (b"e:2\r\n", "error")]:
            with self.subTest(response=response):
                fixture = WireFixture(open_error=response)
                result, _ = self.acquire(fixture)
                self.assertEqual(result[0]["status"], expected)
                self.assertFalse(result[0]["ok"])
                self.assertTrue(fixture.closed)
                self.assert_clean()

    def test_missing_and_permission_do_not_circuit_break(self):
        fixtures = [WireFixture(open_error=b"e:EACCES\r\n"), WireFixture(open_error=b"e:ENOENT\r\n"), WireFixture()]
        with patch.object(acquire_module, "_open_stream", side_effect=fixtures) as transport:
            receipts = acquire_module.acquire("adb", "serial", [target()] * 3, self.destination, set())
        self.assertEqual(transport.call_count, 3)
        self.assertEqual([r["status"] for r in receipts], ["permission_denied", "missing", "collected"])

    def test_inaccessible_transport_circuits_with_all_receipts(self):
        with patch.object(acquire_module, "_open_stream", side_effect=FileNotFoundError("adb missing")) as transport:
            receipts = acquire_module.acquire("adb", "serial", [target()] * 3, self.destination, set(), 120)
        self.assertEqual(transport.call_count, 1)
        self.assertEqual(transport.call_args.args[2], 8)
        self.assertEqual([r["status"] for r in receipts], ["unavailable", "unavailable_not_attempted", "unavailable_not_attempted"])
        self.assert_clean()

    def test_short_timeout_close_disconnect_mutation_fail_and_delete_partial(self):
        cases = [("short", {"short": True}, "error"), ("timeout", {"timeout": True}, "timeout"),
                 ("close", {"close_error": True}, "error"),
                 ("disconnect", {"broken_disconnect": True}, "error"),
                 ("mutation", {"mutate": True}, "error")]
        for name, kwargs, expected in cases:
            with self.subTest(case=name):
                fixture = WireFixture(**kwargs)
                result, _ = self.acquire(fixture)
                self.assertEqual(result[0]["status"], expected)
                self.assertFalse(result[0]["ok"])
                self.assertIsNone(result[0]["local_path"])
                self.assertIn(b"c:7\r\n", fixture.frames)
                self.assertTrue(fixture.closed)
                self.assertFalse(list(self.destination.glob("*.bin")))
                self.assert_clean()

    def test_oversize_and_directory_never_read(self):
        for fixture, limit in [(WireFixture(), 2), (WireFixture(mode=0x41ED), 65536)]:
            receipts, _ = self.acquire(fixture, [target(limit=limit)])
            self.assertEqual(receipts[0]["status"], "error")
            self.assertFalse(any(frame.startswith(b"r:") for frame in fixture.frames))
            self.assert_clean()

    def test_known_duplicates_not_retained(self):
        fixture = WireFixture()
        known = {hashlib.sha256(fixture.payload).hexdigest()}
        receipts, _ = self.acquire(fixture, known=known)
        self.assertEqual(receipts[0]["status"], "known_duplicate_discarded")
        self.assertTrue(receipts[0]["verified"])
        self.assertFalse(list(self.destination.glob("*.bin")))
        self.assertEqual(len(known), 1)
        self.assert_clean()

    def test_same_content_keeps_all_source_paths_once(self):
        with patch.object(acquire_module, "_open_stream", side_effect=[WireFixture(), WireFixture()]):
            receipts = acquire_module.acquire("adb", "serial", [target(), target("/lib/new.so")], self.destination, set())
        self.assertEqual([r["status"] for r in receipts], ["collected", "duplicate_discarded"])
        self.assertEqual([r["path"] for r in receipts], [REMOTE, "/lib/new.so"])
        self.assertEqual(len(list(self.destination.glob("*.bin"))), 1)
        self.assert_clean()

    def test_local_hash_verification_failure_discards_bytes(self):
        with patch.object(acquire_module, "_verify_local", side_effect=qconn.QconnError("hash mismatch")):
            receipts, _ = self.acquire(WireFixture())
        self.assertEqual(receipts[0]["status"], "error")
        self.assertFalse(list(self.destination.glob("*.bin")))
        self.assert_clean()

    def test_zero_length_regular_file_verifies(self):
        receipts, _ = self.acquire(WireFixture(payload=b""))
        self.assertEqual(receipts[0]["status"], "collected")
        self.assertEqual(receipts[0]["bytes"], 0)
        self.assertEqual(receipts[0]["sha256"], hashlib.sha256(b"").hexdigest())

    def test_nonzero_adb_exit_does_not_publish_successful_bytes(self):
        fixture = WireFixture()
        fixture.process = Mock()
        fixture.process.poll.return_value = 1
        receipts, _ = self.acquire(fixture)
        self.assertEqual(receipts[0]["status"], "error")
        self.assertEqual(receipts[0]["phase"], "transport_exit")
        self.assertFalse(list(self.destination.glob("*.bin")))
        self.assert_clean()

    def test_limit_is_not_exceeded_even_for_exactly_full_file(self):
        fixture = WireFixture()
        receipts, _ = self.acquire(fixture, [target(limit=len(fixture.payload))])
        self.assertEqual(receipts[0]["status"], "collected")
        requests = [frame.rstrip().split(b":") for frame in fixture.frames if frame.startswith(b"r:")]
        self.assertTrue(all(int(frame[2], 16) + int(frame[3], 16) <= len(fixture.payload)
                            for frame in requests))

    def test_frame_guard_rejects_unapproved_path_and_unsafe_operations(self):
        guard = qconn.QconnFrameGuard([REMOTE])
        for frame in [b'service launcher\r\n', b'o:"/dev/can0":0\r\n', b'o:"/etc/shadow":0\r\n',
                      b'w:7:0:1\r\n', b'o:"/usr/bin/missing evidence":1\r\n']:
            with self.subTest(frame=frame), self.assertRaises(qconn.QconnError):
                guard.validate(frame)

    def test_keyboard_interrupt_closes_and_deletes_partial(self):
        fixture = WireFixture()
        original_write = fixture.write

        def interrupted(payload):
            if payload.startswith(b"r:"):
                raise KeyboardInterrupt()
            return original_write(payload)

        fixture.write = interrupted
        with self.assertRaises(KeyboardInterrupt):
            self.acquire(fixture)
        self.assertTrue(fixture.closed)
        self.assertIn(b"c:7\r\n", fixture.frames)
        self.assert_clean()


if __name__ == "__main__":
    unittest.main()
