"""Exact-file QNX collection through the already selected Android ADB link.

Only the successful nfogh read dialect recorded in QNX Deep Evidence (2026-08-15)
is used. The adjacent qconn_native.py is an unchanged Research Agent 1.5.6 source
snapshot, SHA-256 9cf3dfade347adadbea436bb78fa1374a34d84d31189f931a2587e48236364c7.
There is no direct Mac route, discovery, QNX launcher, or dialect guessing here.

The caller supplies an evidence-reviewed manifest of exact ordinary file paths.
qconn offers descriptor stat only AFTER opening: this cannot prove that an
unreviewed path/symlink is safe to open. In particular /dev is forbidden before
transport creation, even with O_RDONLY. Files are read, never executed.
"""

from __future__ import annotations

import hashlib
import math
import os
import re
import select
import subprocess
import sys
import tempfile
import time
from pathlib import Path, PurePosixPath
from typing import Any, Dict, List, Set

try:
    from . import qconn_native as qconn
except ImportError:  # Also supports a plain lib directory on sys.path.
    import qconn_native as qconn


ENDPOINT = ("198.18.34.2", "8000")
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_TARGETS = 512
SAFE_ROOTS = (
    "/bin/", "/sbin/", "/lib/", "/lib64/", "/usr/bin/", "/usr/sbin/",
    "/usr/lib/", "/usr/lib64/", "/etc/", "/scripts/", "/proc/boot/",
)
SAFE_EXACT_FILES = frozenset((
    # KX11-VP-Firmware-Evidence.zip, ordinary file, 49,506,320 bytes,
    # SHA-256 4a0cd3a681364059ace2c857d4d7436e0902660c43601b48d0ef735e86d23680.
    "/vm/images_lv/linux-lv.img",
))
SECRET_PARTS = frozenset((
    "shadow", "passwd", "pwd.db", "spwd.db", "master.passwd", "credentials",
    "credential", "secrets", "secret", "tokens", "token", "private", ".ssh",
    ".gnupg", "ssh", "ssl", "tls", "keys", "keystore", "reset_stats",
))


class RejectedTarget(ValueError):
    """A request was rejected before any QNX access."""


def validate_target(target: Dict[str, Any]) -> str:
    """Check a trusted manifest row, with non-overridable path exclusions."""
    if not isinstance(target, dict):
        raise RejectedTarget("target must be a manifest object")
    path = target.get("path")
    try:
        normalized = qconn.validate_remote_path(path)
    except (qconn.QconnError, TypeError, ValueError) as exc:
        raise RejectedTarget(str(exc)) from exc
    if normalized != path or any(char in path for char in "*?[]:"):
        raise RejectedTarget("target must be one canonical exact path without wildcards")
    if path.startswith("/dev"):
        raise RejectedTarget("device paths are forbidden even for read-only open")
    if path.startswith("/proc") and not path.startswith("/proc/boot/"):
        raise RejectedTarget("only exact ordinary /proc/boot files may be requested")
    if path not in SAFE_EXACT_FILES and not path.startswith(SAFE_ROOTS):
        raise RejectedTarget("path is outside reviewed executable/library/config locations")
    parts = PurePosixPath(path).parts[1:]
    if path.startswith("/proc/boot/") and len(parts) != 3:
        raise RejectedTarget("/proc/boot target must name one direct file")
    if any(part.casefold() in SECRET_PARTS for part in parts):
        raise RejectedTarget("secret or state-changing path is forbidden")
    name = parts[-1].casefold()
    if (name.startswith(("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "ssh_host_"))
            or name.endswith((".key", ".pem", ".p12", ".pfx", ".jks", ".keystore"))
            or re.search(r"(?:^|[._-])(?:passwords?|credentials?|secrets?|tokens?)(?:[._-]|$)", name)):
        raise RejectedTarget("credential/key path is forbidden")
    limit = target.get("max_bytes")
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= MAX_FILE_BYTES:
        raise RejectedTarget("max_bytes must be an integer between 1 and 268435456")
    for key in ("id", "reason"):
        value = target.get(key)
        if not isinstance(value, str) or not value.strip() or len(value) > 4096:
            raise RejectedTarget("manifest requires a nonempty bounded " + key)
        if any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise RejectedTarget("manifest " + key + " contains control characters")
    return path


class BoundedProcessByteStream(qconn.ProcessByteStream):
    """Keep the proven framing while bounding Mac pipe writes and line length."""

    def __init__(self, *args: Any, **kwargs: Any):
        super().__init__(*args, **kwargs)
        os.set_blocking(self.stdin.fileno(), False)

    def write(self, payload: bytes) -> None:
        if self.closed or not isinstance(payload, bytes) or not payload:
            raise qconn.QconnError("invalid qconn write")
        deadline = time.monotonic() + self.default_timeout
        offset = 0
        while offset < len(payload):
            self._check_abort()
            left = deadline - time.monotonic()
            if left <= 0:
                raise qconn.QconnAbortError("qconn pipe write timed out")
            try:
                _, ready, _ = select.select([], [self.stdin.fileno()], [], min(left, 0.25))
                if not ready:
                    continue
                count = os.write(self.stdin.fileno(), payload[offset:])
            except BlockingIOError:
                continue
            except (OSError, ValueError) as exc:
                raise qconn.QconnError("qconn transport rejected client data") from exc
            if count <= 0:
                raise qconn.QconnError("qconn transport made no write progress")
            chunk = payload[offset:offset + count]
            self.tx_sha256.update(chunk)
            self.tx_bytes += count
            remaining = qconn.TRANSCRIPT_PREVIEW_BYTES - len(self.tx_preview)
            if remaining > 0:
                self.tx_preview.extend(chunk[:remaining])
            offset += count

    def read_until(self, delimiter: bytes, maximum: int = qconn.MAX_LINE_BYTES,
                   timeout: float = None) -> bytes:
        result = super().read_until(delimiter, maximum, timeout)
        if len(result) > maximum:
            raise qconn.QconnError("qconn line exceeded the configured bound")
        return result


class StrictReadOnlyClient(qconn.QconnReadOnlyClient):
    def disconnect(self) -> None:
        try:
            if self.handshake:
                self._write(b"q")
        finally:
            self.stream.close()


def _open_stream(adb: str, serial: str, timeout: float, abort_check: Any) -> BoundedProcessByteStream:
    # All remote command words are constants: no shell interpolation of targets.
    argv = [adb, "-s", serial, "shell", "-T", "toybox", "nc", "-w", "30", *ENDPOINT]
    process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, bufsize=0, shell=False,
                               start_new_session=True)
    try:
        return BoundedProcessByteStream(process, default_timeout=timeout, abort_check=abort_check)
    except BaseException:
        process.kill()
        process.communicate(timeout=2)
        raise


def _status(exc: Exception, phase: str) -> str:
    message = str(exc).casefold()
    if isinstance(exc, RejectedTarget):
        return "rejected"
    if isinstance(exc, (qconn.QconnAbortError, TimeoutError, subprocess.TimeoutExpired)):
        return "timeout"
    if isinstance(exc, PermissionError) or any(
            token in message for token in ("permission denied", "access denied", "eacces", "eperm")):
        return "permission_denied"
    # A missing adb binary is not a missing QNX target. Numeric qconn error bodies
    # are opaque, so only an explicit textual response can establish absence.
    if phase == "open" and isinstance(exc, qconn.QconnError) and any(
            token in message for token in ("enoent", "no such file or directory")):
        return "missing"
    if "ended or timed out" in message:
        return "unavailable"
    if phase in ("transport", "handshake", "broker", "file_service"):
        return "unavailable"
    return "error"


def _verify_local(path: Path, expected_bytes: int, expected_sha256: str) -> None:
    total = 0
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            total += len(chunk)
            digest.update(chunk)
    if total != expected_bytes or digest.hexdigest() != expected_sha256:
        raise qconn.QconnError("local byte count or SHA-256 verification failed")


def acquire(adb: str, serial: str, remote_paths: List[Dict[str, Any]], destination: Path,
            known_hashes: Set[str], timeout: float = 15) -> List[Dict[str, Any]]:
    """Return one receipt per exact target; retain only verified new file bytes.

    timeout is the total per-target I/O budget, plus at most one second for the
    protocol close attempt and four seconds for bounded local child teardown.
    Connection/service setup has a separate maximum of eight seconds. A fresh connection per target
    isolates malformed/failed streams. No real transport is opened for rejected
    paths. known_hashes is never mutated. Output filenames use SHA-256, while
    every original path/id/reason remains present in the returned receipts.
    """
    if not isinstance(adb, str) or not adb or "\x00" in adb:
        raise ValueError("adb must name the selected executable")
    if not isinstance(serial, str) or not serial or any(ord(char) < 32 for char in serial):
        raise ValueError("serial must identify one selected ADB device")
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or not 0.2 <= timeout <= 3600:
        raise ValueError("timeout must be finite and between 0.2 and 3600 seconds")
    if not isinstance(remote_paths, list) or len(remote_paths) > MAX_TARGETS:
        raise ValueError("remote_paths must be a manifest list of at most 512 exact files")
    known = {value.lower() for value in known_hashes
             if isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value)}
    destination = Path(destination)
    seen: Dict[str, str] = {}
    receipts: List[Dict[str, Any]] = []
    unavailable_cause = None
    for target in remote_paths:
        source = target if isinstance(target, dict) else {}
        receipt: Dict[str, Any] = {
            "id": source.get("id"), "path": source.get("path"), "reason": source.get("reason"),
            "transport": "adb-shell-qconn", "endpoint": ":".join(ENDPOINT),
            "dialect": "qconn-native-nfogh", "status": "error", "ok": False,
            "bytes": 0, "sha256": None, "local_path": None,
        }
        receipts.append(receipt)
        partial = None
        stream = None
        client = None
        descriptor = None
        failure = None
        phase = "validation"
        digest = hashlib.sha256()
        try:
            path = validate_target(target)
            if unavailable_cause is not None:
                receipt.update(status="unavailable_not_attempted", error=unavailable_cause,
                               phase="transport", attempted=False)
                continue
            destination.mkdir(parents=True, exist_ok=True)
            phase = "local_output"
            fd, temp_name = tempfile.mkstemp(prefix=".qnx-", suffix=".partial", dir=str(destination))
            partial = Path(temp_name)
            # fdopen closes the descriptor even when transport creation fails.
            with os.fdopen(fd, "wb") as output:
                deadline = time.monotonic() + timeout
                connect_deadline = time.monotonic() + min(timeout, 8.0)

                def abort_check() -> None:
                    limit = connect_deadline if phase in ("transport", "handshake", "broker", "file_service") else deadline
                    if time.monotonic() >= limit:
                        raise qconn.QconnAbortError("per-target QNX acquisition timed out")

                phase = "transport"
                stream = _open_stream(adb, serial, min(timeout, 8.0), abort_check)
                receipt["attempted"] = True
                client = StrictReadOnlyClient(stream, allowed_paths=(path,))
                phase = "handshake"
                receipt["handshake"] = client.connect()
                phase = "broker"
                receipt["broker"] = client.broker_info()
                phase = "file_service"
                client.activate_file_service()
                stream.default_timeout = timeout
                phase = "open"
                descriptor = client.open_readonly(path)
                phase = "stat"
                metadata = client.stat(descriptor)
                receipt["stat"] = metadata
                if metadata.get("mode", 0) & qconn.S_IFMT != qconn.S_IFREG:
                    raise qconn.QconnError("QNX target is not a regular file")
                size = metadata.get("size", -1)
                if not 0 <= size <= target["max_bytes"]:
                    raise qconn.QconnError("QNX target exceeds its explicit max_bytes")
                phase = "read"
                while receipt["bytes"] < size:
                    chunk = client.read_chunk(descriptor, receipt["bytes"],
                                              min(client.max_read_chunk, size - receipt["bytes"]))
                    if not chunk:
                        raise qconn.QconnError("QNX file ended before its advertised size")
                    written = output.write(chunk)
                    if written != len(chunk):
                        raise qconn.QconnError("local file write was incomplete")
                    receipt["bytes"] += len(chunk)
                    digest.update(chunk)
                # This zero-length EOF reply catches growth without retaining an
                # extra data byte beyond the caller's explicit file size bound.
                if size < target["max_bytes"] and client.read_chunk(descriptor, size, 1):
                    raise qconn.QconnError("QNX file grew beyond its advertised size")
                phase = "verify_remote"
                final_stat = client.stat(descriptor)
                if any(final_stat.get(key) != metadata.get(key)
                       for key in ("ino", "dev", "mode", "size", "mtime", "ctime")):
                    raise qconn.QconnError("QNX metadata changed during acquisition")
                output.flush()
                os.fsync(output.fileno())
                receipt["sha256"] = digest.hexdigest()
        except Exception as exc:
            failure = exc
            receipt["status"] = _status(exc, phase)
            receipt["error"] = str(exc)[:2000]
            receipt["phase"] = phase
            if (phase in ("transport", "handshake", "broker", "file_service")
                    and receipt["status"] != "permission_denied"):
                unavailable_cause = "Previous QNX transport/service attempt failed: " + str(exc)[:1000]
        finally:
            # A timed-out operation still gets a separately bounded close attempt.
            # Any cleanup error prevents success; pinned client's old download()
            # tolerance for a missing close response is deliberately not used.
            cleanup_errors = []
            if stream is not None:
                cleanup_deadline = time.monotonic() + 1.0

                def cleanup_check() -> None:
                    if time.monotonic() >= cleanup_deadline:
                        raise qconn.QconnAbortError("QNX close/disconnect timed out")

                stream.abort_check = cleanup_check
                stream.default_timeout = 1.0
            if client is not None:
                if descriptor is not None:
                    try:
                        client.close_descriptor(descriptor)
                    except Exception as exc:
                        cleanup_errors.append(str(exc)[:1000])
                        if failure is None:
                            failure = exc
                            receipt.update(status=_status(exc, "close"), error=str(exc)[:2000], phase="close")
                try:
                    client.disconnect()
                except Exception as exc:
                    cleanup_errors.append(str(exc)[:1000])
                    if failure is None:
                        failure = exc
                        receipt.update(status=_status(exc, "disconnect"), error=str(exc)[:2000], phase="disconnect")
                receipt["frame_guard"] = client.guard.summary()
            elif stream is not None:
                stream.close()
            process = getattr(stream, "process", None)
            if process is not None:
                code = process.poll()
                receipt["transport_exit_code"] = code
                if code is not None and code > 0 and failure is None:
                    failure = qconn.QconnError("ADB byte transport exited with status %d" % code)
                    receipt.update(status="error", error=str(failure), phase="transport_exit")
            if cleanup_errors:
                receipt["cleanup_errors"] = cleanup_errors
            if sys.exc_info()[0] is not None and partial is not None:
                partial.unlink(missing_ok=True)
        try:
            if failure is None and partial is not None:
                phase = "verify_local"
                _verify_local(partial, receipt["bytes"], receipt["sha256"])
                sha = receipt["sha256"]
                if sha in known:
                    receipt["status"] = "known_duplicate_discarded"
                elif sha in seen:
                    receipt.update(status="duplicate_discarded", duplicate_of=seen[sha])
                else:
                    final = destination / (sha + ".bin")
                    if final.exists() or final.is_symlink():
                        if final.is_symlink() or not final.is_file():
                            raise qconn.QconnError("local content path is not an ordinary file")
                        _verify_local(final, receipt["bytes"], sha)
                        receipt.update(status="duplicate_discarded", duplicate_of=str(final))
                    else:
                        # link is an atomic no-overwrite publication on the Mac.
                        os.link(partial, final)
                        receipt.update(status="collected", ok=True, local_path=str(final))
                    seen[sha] = str(final)
                receipt["verified"] = True
        except Exception as exc:
            receipt.update(status=_status(exc, phase), ok=False, error=str(exc)[:2000], phase=phase)
        finally:
            if partial is not None:
                partial.unlink(missing_ok=True)
    return receipts
