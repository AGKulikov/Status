#!/usr/bin/env python3
"""Minimal read-only QNX qconn client primitives.

The module intentionally implements only broker information and the read side
of qconn's ``file`` service.  It has no launcher, control, write, delete,
rename, chmod, or process-management operation.  Transport creation remains in
the trusted ADB gateway so generated analysis code never receives ADB access.

Python 3.8+, standard library only.
"""

from __future__ import annotations

import os
import hashlib
import re
import selectors
import signal
import subprocess
import time
from pathlib import PurePosixPath
from typing import Any, BinaryIO, Callable, Dict, Iterable, List, Optional


QCONN_GREETING = b"QCONN\r\n"
QCONN_TELNET_NEGOTIATION = b"\xff\xfd\x22"
MAX_LINE_BYTES = 64 * 1024
FILE_READ_CHUNK = 2048
TRANSCRIPT_PREVIEW_BYTES = 8 * 1024
MAX_DIRECTORY_ENTRIES = 50_000
MAX_DIRECTORY_RECORDS = 100_000
MAX_DIRECTORY_RECORD_BYTES = 1024 * 1024
MAX_DIRECTORY_TOTAL_BYTES = 64 * 1024 * 1024
MAX_DIRECTORY_NONPROGRESS_RECORDS = 128
DEFAULT_DIRECTORY_NONPROGRESS_RECORDS = 16
S_IFMT = 0xF000
S_IFREG = 0x8000
S_IFDIR = 0x4000


class QconnError(RuntimeError):
    pass


class QconnAbortError(QconnError):
    """A shared deadline or operator control interrupted protocol I/O."""


class QconnNotDirectoryError(QconnError):
    """A directory-only open was explicitly rejected with ENOTDIR."""


class QconnFrameGuard:
    """Allow only broker information and GET-only file-service frames."""

    _FIXED = (
        re.compile(br"^info\r\n$"),
        re.compile(br"^versions file\r\n$"),
        re.compile(br"^service file\r\n$"),
        re.compile(br"^s:[0-9a-f]{1,12}\r\n$"),
        re.compile(br"^r:[0-9a-f]{1,12}:[0-9a-f]{1,16}:[0-9a-f]{1,8}(?::[01])?\r\n$"),
        re.compile(br"^c:[0-9a-f]{1,12}\r\n$"),
        re.compile(br"^q$"),
    )
    _OPEN = re.compile(br'^o:"([^"\r\n]+)":0(?::4000)?\r\n$')
    _TELNET_REPLIES = frozenset((b"\xff\xfc\x22", b"\xff\xfe\x22"))

    def __init__(self, allowed_paths: Iterable[str]):
        self.allowed_paths = {validate_remote_path(path) for path in allowed_paths}
        self.frames = 0
        self.bytes = 0
        self._sha256 = hashlib.sha256()

    def grant_path(self, path: str) -> str:
        """Grant one validated exact path for subsequent read-only open frames."""
        validated = validate_remote_path(path)
        self.allowed_paths.add(validated)
        return validated

    def validate(self, payload: bytes) -> None:
        if not isinstance(payload, bytes) or not payload or len(payload) > 4096:
            raise QconnError("qconn frame guard rejected an invalid frame")
        matched_open = self._OPEN.fullmatch(payload)
        if payload in self._TELNET_REPLIES:
            pass
        elif matched_open:
            path = matched_open.group(1).decode("utf-8", "strict")
            if path not in self.allowed_paths:
                raise QconnError("qconn frame guard rejected an unapproved path")
        elif not any(pattern.fullmatch(payload) for pattern in self._FIXED):
            raise QconnError("qconn frame guard rejected a non-read operation")
        self.frames += 1
        self.bytes += len(payload)
        self._sha256.update(len(payload).to_bytes(4, "big"))
        self._sha256.update(payload)

    def summary(self) -> Dict[str, Any]:
        allowed = sorted(self.allowed_paths)
        allowed_payload = ("\n".join(allowed) + ("\n" if allowed else "")).encode("utf-8")
        return {
            "frames": self.frames,
            "bytes": self.bytes,
            "frames_sha256": self._sha256.hexdigest(),
            "allowed_paths": allowed[:256],
            "allowed_path_count": len(allowed),
            "allowed_paths_sha256": hashlib.sha256(allowed_payload).hexdigest(),
            "allowed_paths_truncated": len(allowed) > 256,
        }


def validate_remote_path(path: str) -> str:
    if not isinstance(path, str) or not path.startswith("/"):
        raise QconnError("qconn path must be absolute")
    if path.startswith("//"):
        raise QconnError("qconn path must use a single absolute root")
    if len(path) > 2048 or any(ord(ch) < 32 or ord(ch) == 127 for ch in path):
        raise QconnError("qconn path contains control characters or is too long")
    if any(ch in path for ch in ('"', "\\", "\r", "\n", "\x00")):
        raise QconnError("qconn path contains protocol metacharacters")
    pure = PurePosixPath(path)
    if ".." in pure.parts:
        raise QconnError("qconn path contains parent traversal")
    return str(pure)


class ProcessByteStream:
    """Bounded interactive byte stream around an ADB ``shell -T`` child."""

    def __init__(self, process: subprocess.Popen, default_timeout: float = 15.0,
                 abort_check: Optional[Callable[[], None]] = None):
        if process.stdin is None or process.stdout is None or process.stderr is None:
            raise QconnError("interactive transport requires all three child pipes")
        self.process = process
        self.stdin = process.stdin
        self.stdout = process.stdout
        self.stderr = process.stderr
        self.default_timeout = max(0.2, float(default_timeout))
        self.abort_check = abort_check
        self.buffer = bytearray()
        self.stderr_buffer = bytearray()
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.stdout, selectors.EVENT_READ, "stdout")
        self.selector.register(self.stderr, selectors.EVENT_READ, "stderr")
        self.closed = False
        self.rx_sha256 = hashlib.sha256()
        self.tx_sha256 = hashlib.sha256()
        self.rx_bytes = 0
        self.tx_bytes = 0
        self.rx_preview = bytearray()
        self.tx_preview = bytearray()

    def _check_abort(self) -> None:
        if self.abort_check is None:
            return
        try:
            self.abort_check()
        except QconnAbortError:
            raise
        except Exception as exc:
            raise QconnAbortError("qconn stream interrupted by deadline/control") from exc

    def _fill(self, timeout: Optional[float] = None) -> bool:
        deadline = time.monotonic() + (self.default_timeout if timeout is None else max(0.0, timeout))
        while time.monotonic() <= deadline:
            self._check_abort()
            remaining = max(0.0, deadline - time.monotonic())
            # Poll in short slices so Stop/Pause and the shared live deadline
            # are observed even while a target is silent or sends slowly.
            events = self.selector.select(min(remaining, 0.25))
            if not events:
                if time.monotonic() >= deadline:
                    return False
                continue
            saw_stdout = False
            for key, _ in events:
                try:
                    chunk = os.read(key.fileobj.fileno(), 64 * 1024)
                except (OSError, ValueError):
                    chunk = b""
                if key.data == "stdout":
                    if chunk:
                        self.rx_sha256.update(chunk)
                        self.rx_bytes += len(chunk)
                        if len(self.rx_preview) < TRANSCRIPT_PREVIEW_BYTES:
                            self.rx_preview.extend(
                                chunk[:TRANSCRIPT_PREVIEW_BYTES - len(self.rx_preview)]
                            )
                        self.buffer.extend(chunk)
                        saw_stdout = True
                    else:
                        try:
                            self.selector.unregister(self.stdout)
                        except Exception:
                            pass
                else:
                    if chunk:
                        remaining_stderr = max(0, 1024 * 1024 - len(self.stderr_buffer))
                        self.stderr_buffer.extend(chunk[:remaining_stderr])
                    else:
                        try:
                            self.selector.unregister(self.stderr)
                        except Exception:
                            pass
            if saw_stdout:
                return True
            if self.process.poll() is not None:
                return False
        return False

    def read_exact(self, length: int, timeout: Optional[float] = None) -> bytes:
        if length < 0 or length > 256 * 1024 * 1024:
            raise QconnError("invalid qconn read length")
        deadline = time.monotonic() + (self.default_timeout if timeout is None else max(0.0, timeout))
        while len(self.buffer) < length:
            self._check_abort()
            if not self._fill(max(0.0, deadline - time.monotonic())):
                detail = bytes(self.stderr_buffer).decode("utf-8", "replace")[:500]
                raise QconnError("qconn stream ended or timed out" + (": " + detail if detail else ""))
        result = bytes(self.buffer[:length])
        del self.buffer[:length]
        return result

    def read_until(self, delimiter: bytes, maximum: int = MAX_LINE_BYTES,
                   timeout: Optional[float] = None) -> bytes:
        if not delimiter or maximum <= 0:
            raise QconnError("invalid qconn delimiter read")
        deadline = time.monotonic() + (self.default_timeout if timeout is None else max(0.0, timeout))
        while True:
            self._check_abort()
            index = self.buffer.find(delimiter)
            if index >= 0:
                end = index + len(delimiter)
                result = bytes(self.buffer[:end])
                del self.buffer[:end]
                return result
            if len(self.buffer) > maximum:
                raise QconnError("qconn line exceeded the configured bound")
            if not self._fill(max(0.0, deadline - time.monotonic())):
                detail = bytes(self.stderr_buffer).decode("utf-8", "replace")[:500]
                raise QconnError("qconn stream ended or timed out" + (": " + detail if detail else ""))

    def write(self, payload: bytes) -> None:
        if self.closed or not isinstance(payload, (bytes, bytearray)):
            raise QconnError("invalid qconn write")
        self._check_abort()
        try:
            self.tx_sha256.update(bytes(payload))
            self.tx_bytes += len(payload)
            if len(self.tx_preview) < TRANSCRIPT_PREVIEW_BYTES:
                self.tx_preview.extend(
                    bytes(payload)[:TRANSCRIPT_PREVIEW_BYTES - len(self.tx_preview)]
                )
            self.stdin.write(bytes(payload))
            self.stdin.flush()
        except (BrokenPipeError, OSError, ValueError) as exc:
            raise QconnError("qconn transport rejected client data") from exc

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.stdin.close()
        except Exception:
            pass
        if self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                try:
                    self.process.terminate()
                except Exception:
                    pass
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError, OSError):
                    try:
                        self.process.kill()
                    except Exception:
                        pass
                try:
                    self.process.wait(timeout=2)
                except Exception:
                    pass
        try:
            self.selector.close()
        except Exception:
            pass
        for stream in (self.stdout, self.stderr):
            try:
                stream.close()
            except Exception:
                pass

    def transcript_summary(self) -> Dict[str, Any]:
        return {
            "rx_bytes": self.rx_bytes,
            "tx_bytes": self.tx_bytes,
            "rx_sha256": self.rx_sha256.hexdigest(),
            "tx_sha256": self.tx_sha256.hexdigest(),
            "rx_preview_hex": bytes(self.rx_preview).hex().upper(),
            "tx_preview_hex": bytes(self.tx_preview).hex().upper(),
            "stderr_preview": bytes(self.stderr_buffer).decode("utf-8", "replace")[:2000],
        }


class QconnReadOnlyClient:
    """Strict read-only qconn broker/file-service client."""

    def __init__(self, stream: ProcessByteStream, negotiation_timeout: float = 2.0,
                 dialect: Optional[Dict[str, Any]] = None,
                 allowed_paths: Iterable[str] = ()):
        self.stream = stream
        self.negotiation_timeout = max(0.1, float(negotiation_timeout))
        self.dialect = dict(dialect or {})
        try:
            self.greeting = bytes.fromhex(self.dialect.get("greeting_hex", "51434F4E4E0D0A"))
            self.negotiation = bytes.fromhex(self.dialect.get("negotiation_hex", "FFFD22"))
            self.telnet_reply = bytes.fromhex(self.dialect.get("telnet_reply_hex", ""))
        except (TypeError, ValueError) as exc:
            raise QconnError("qconn dialect contains invalid hexadecimal bytes") from exc
        self.prompt_mode = self.dialect.get("prompt_mode", "none")
        self.read_request_variant = self.dialect.get("read_request_variant", "with_type_zero")
        self.metadata_source = self.dialect.get("metadata_source", "stat")
        self.send_versions_file = bool(self.dialect.get("send_versions_file", False))
        self.open_metadata_base = int(self.dialect.get("open_metadata_base", 16))
        self.open_mode_field = int(self.dialect.get("open_mode_field", 2))
        self.open_size_field = int(self.dialect.get("open_size_field", 3))
        self.open_path_field = int(self.dialect.get("open_path_field", 4))
        self.descriptor_base = int(self.dialect.get("descriptor_base", 10))
        self.stat_base = int(self.dialect.get("stat_base", 16))
        self.read_count_base = int(self.dialect.get("read_count_base", 16))
        self.read_count_field = int(self.dialect.get("read_count_field", 1))
        self.directory_length_base = int(self.dialect.get("directory_length_base", 16))
        self.directory_length_field = int(self.dialect.get("directory_length_field", 2))
        self.max_read_chunk = min(FILE_READ_CHUNK, max(64, int(self.dialect.get("max_read_chunk", FILE_READ_CHUNK))))
        if not self.greeting or len(self.greeting) > 64 or len(self.negotiation) > 64:
            raise QconnError("qconn dialect handshake is out of bounds")
        if self.telnet_reply not in (b"", b"\xff\xfc\x22", b"\xff\xfe\x22"):
            raise QconnError("qconn dialect may only refuse the observed Telnet LINEMODE option")
        if self.prompt_mode not in ("none", "qcl"):
            raise QconnError("unsupported qconn prompt mode")
        if self.read_request_variant not in ("with_type_zero", "without_type"):
            raise QconnError("unsupported qconn read request variant")
        if self.metadata_source not in ("stat", "open_response"):
            raise QconnError("unsupported qconn metadata source")
        if self.open_metadata_base not in (10, 16):
            raise QconnError("unsupported qconn open metadata base")
        if any(field not in (1, 2, 3, 4) for field in (
                self.open_mode_field, self.open_size_field, self.open_path_field)):
            raise QconnError("unsupported qconn open metadata field")
        if self.descriptor_base not in (10, 16):
            raise QconnError("unsupported qconn descriptor base")
        if any(base not in (10, 16) for base in (
            self.stat_base, self.read_count_base, self.directory_length_base,
        )):
            raise QconnError("unsupported qconn integer base")
        if self.read_count_field not in (1, 2, 3) or self.directory_length_field not in (1, 2, 3):
            raise QconnError("unsupported qconn response field")
        self.guard = QconnFrameGuard(allowed_paths)
        self.handshake: Dict[str, Any] = {}
        self.service: Optional[str] = None
        self.open_metadata: Dict[int, Dict[str, int]] = {}
        self.file_version_reply: Optional[str] = None

    def grant_path(self, path: str) -> str:
        """Delegate one trusted, exact path grant to the protocol guard."""
        return self.guard.grant_path(path)

    def _write(self, payload: bytes) -> None:
        self.guard.validate(payload)
        self.stream.write(payload)

    def connect(self) -> Dict[str, Any]:
        greeting = self.stream.read_until(b"\r\n", maximum=64)
        if greeting != self.greeting:
            raise QconnError("unexpected qconn greeting: " + greeting[:64].hex())
        negotiation = self.stream.read_exact(len(self.negotiation), timeout=self.negotiation_timeout) if self.negotiation else b""
        if negotiation != self.negotiation:
            raise QconnError("unexpected qconn negotiation: " + negotiation.hex())
        if self.telnet_reply:
            self._write(self.telnet_reply)
        self.handshake = {
            "greeting_hex": greeting.hex().upper(),
            "negotiation_hex": negotiation.hex().upper(),
            "telnet_reply_hex": self.telnet_reply.hex().upper(),
            "prompt_mode": self.prompt_mode,
            "read_request_variant": self.read_request_variant,
            "metadata_source": self.metadata_source,
        }
        return dict(self.handshake)

    def broker_info(self) -> Dict[str, str]:
        self._write(b"info\r\n")
        line = self._line().encode("utf-8")
        result: Dict[str, str] = {}
        for token in line[:-2].decode("utf-8", "replace").split():
            if "=" in token:
                key, value = token.split("=", 1)
                if key and len(key) <= 80 and len(value) <= 1024:
                    result[key] = value
        if not result:
            raise QconnError("qconn info response contained no key/value fields")
        return result

    def activate_file_service(self) -> None:
        if self.send_versions_file:
            self._write(b"versions file\r\n")
            version_reply = self._line()
            if version_reply.startswith("e:"):
                raise QconnError("qconn file version check failed: " + version_reply[:512])
            self.file_version_reply = version_reply.rstrip("\r\n")
            try:
                file_version = int(self.file_version_reply.split()[0], 10)
            except (ValueError, IndexError) as exc:
                raise QconnError("qconn returned an invalid file-service version") from exc
            if file_version < 256:
                raise QconnError("qconn file-service version is unsupported")
        self._write(b"service file\r\n")
        reply = self._line()
        if reply != "OK\r\n":
            raise QconnError("qconn file service activation failed: " + reply[:512])
        self.service = "file"

    def _line(self) -> str:
        prompts = (b"<qconn-file>", b"<qconn-broker>") if self.prompt_mode == "qcl" else ()
        while True:
            raw = self.stream.read_until(b"\r\n")
            body = raw[:-2].lstrip(b" \t\r\n")
            stripped = True
            while stripped:
                stripped = False
                for prompt in prompts:
                    if body.startswith(prompt):
                        body = body[len(prompt):].lstrip(b" \t\r\n")
                        stripped = True
            if not body or body == b"error linemode-or-echo-not-supported":
                continue
            return body.decode("utf-8", "replace") + "\r\n"

    def _format_descriptor(self, descriptor: int) -> str:
        if descriptor < 0 or descriptor > 1_000_000:
            raise QconnError("qconn descriptor is out of range")
        return format(descriptor, "x") if self.descriptor_base == 16 else str(descriptor)

    @staticmethod
    def _directory_bound(value: int, name: str, maximum: int) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise QconnError("qconn %s must be an integer" % name)
        if value < 1 or value > maximum:
            raise QconnError(
                "qconn %s must be between 1 and %d" % (name, maximum)
            )
        return value

    @staticmethod
    def _explicit_not_directory_error(fields: List[str]) -> bool:
        """Recognize only an explicit textual ENOTDIR wire response.

        Public qconn clients expose the ``e:...`` body as an opaque string, so
        a bare numeric value is not assumed to be an errno.  Requiring one of
        the standard textual names keeps malformed, permission, timeout and
        other open failures from being mistaken for a regular file.
        """
        for field in fields[1:]:
            normalized = " ".join(field.strip().split()).casefold()
            if normalized in ("enotdir", "not a directory"):
                return True
            if re.fullmatch(r"enotdir\s*\(\s*(?:errno\s*)?20\s*\)", normalized):
                return True
        return False

    @staticmethod
    def _fields(line: str, minimum: int = 1,
                directory_open: bool = False) -> List[str]:
        fields = line.rstrip("\r\n").split(":")
        if fields[0] == "e":
            if (directory_open
                    and QconnReadOnlyClient._explicit_not_directory_error(fields)):
                raise QconnNotDirectoryError(
                    "qconn directory open rejected with ENOTDIR: "
                    + ":".join(fields[1:])[:1000]
                )
            raise QconnError("qconn file-service error: " + ":".join(fields[1:])[:1000])
        if len(fields) < minimum:
            raise QconnError("malformed qconn file-service response")
        if fields[0] != "o":
            raise QconnError("unexpected qconn file-service response: " + line[:1000])
        return fields

    def open_readonly(self, path: str, directory: bool = False) -> int:
        path = validate_remote_path(path)
        command = 'o:"%s":0' % path
        if directory:
            command += ":4000"
        self._write((command + "\r\n").encode("utf-8"))
        fields = self._fields(
            self._line(), minimum=2, directory_open=directory,
        )
        try:
            descriptor = int(fields[1], self.descriptor_base)
        except ValueError as exc:
            raise QconnError("qconn returned an invalid file descriptor") from exc
        if descriptor < 0 or descriptor > 1_000_000:
            raise QconnError("qconn returned an out-of-range file descriptor")
        if self.metadata_source == "open_response":
            largest = max(self.open_mode_field, self.open_size_field, self.open_path_field)
            if largest >= len(fields):
                raise QconnError("qconn open response lacks required metadata fields")
            try:
                mode = int(fields[self.open_mode_field], self.open_metadata_base)
                size = int(fields[self.open_size_field], self.open_metadata_base)
            except ValueError as exc:
                raise QconnError("qconn open response has invalid mode/size") from exc
            returned_path = fields[self.open_path_field]
            if returned_path.startswith('"') and returned_path.endswith('"'):
                returned_path = returned_path[1:-1]
            if returned_path != path or mode < 0 or size < 0:
                raise QconnError("qconn open response metadata/path mismatch")
            self.open_metadata[descriptor] = {"mode": mode, "size": size}
        return descriptor

    def stat(self, descriptor: int) -> Dict[str, int]:
        self._write(("s:%s\r\n" % self._format_descriptor(descriptor)).encode("ascii"))
        fields = self._fields(self._line(), minimum=16)
        names = (
            "ino", "size", "dev", "rdev", "uid", "gid", "mtime", "atime",
            "ctime", "mode", "nlink", "blocksize", "nblocks", "blksize", "blocks",
        )
        try:
            values = [int(value, self.stat_base) for value in fields[1:16]]
        except ValueError as exc:
            raise QconnError("qconn stat response contained a non-hex field") from exc
        return dict(zip(names, values))

    def read_chunk(self, descriptor: int, offset: int, size: int = FILE_READ_CHUNK) -> bytes:
        if descriptor < 0 or offset < 0 or size < 1 or size > self.max_read_chunk:
            raise QconnError("invalid bounded qconn read request")
        suffix = ":0" if self.read_request_variant == "with_type_zero" else ""
        command = "r:%s:%x:%x%s\r\n" % (
            self._format_descriptor(descriptor), offset, size, suffix,
        )
        self._write(command.encode("ascii"))
        fields = self._fields(self._line(), minimum=2)
        try:
            if self.read_count_field >= len(fields):
                raise ValueError("read count field is absent")
            count = int(fields[self.read_count_field], self.read_count_base)
        except ValueError as exc:
            raise QconnError("qconn read count was not hexadecimal") from exc
        if count < 0 or count > size:
            raise QconnError("qconn returned an out-of-range read count")
        return self.stream.read_exact(count) if count else b""

    def close_descriptor(self, descriptor: int) -> None:
        self._write(("c:%s\r\n" % self._format_descriptor(descriptor)).encode("ascii"))
        self._fields(self._line(), minimum=1)

    def list_directory(
            self, path: str, maximum_entries: int = 10000, *,
            maximum_records: int = MAX_DIRECTORY_RECORDS,
            maximum_bytes: int = MAX_DIRECTORY_TOTAL_BYTES,
            maximum_nonprogress_records: int = DEFAULT_DIRECTORY_NONPROGRESS_RECORDS,
    ) -> List[str]:
        """List a directory with independent entry, wire and progress bounds.

        ``path`` and the positional ``maximum_entries`` argument retain the
        original API.  Keyword-only bounds let a trusted caller tighten the
        defaults without permitting it to exceed the client hard limits.
        Every decoded record counts, including empty, ``.`` and ``..``
        records, so rejected data cannot evade the work bound.
        """
        maximum_entries = self._directory_bound(
            maximum_entries, "maximum_entries", MAX_DIRECTORY_ENTRIES,
        )
        maximum_records = self._directory_bound(
            maximum_records, "maximum_records", MAX_DIRECTORY_RECORDS,
        )
        maximum_bytes = self._directory_bound(
            maximum_bytes, "maximum_bytes", MAX_DIRECTORY_TOTAL_BYTES,
        )
        maximum_nonprogress_records = self._directory_bound(
            maximum_nonprogress_records, "maximum_nonprogress_records",
            MAX_DIRECTORY_NONPROGRESS_RECORDS,
        )
        descriptor = self.open_readonly(path, directory=True)
        entries: List[str] = []
        index = 0
        raw_records = 0
        total_bytes = 0
        nonprogress_records = 0
        synchronized = True
        failure: Optional[BaseException] = None
        try:
            while len(entries) < maximum_entries:
                self._write(("r:%s:%x:400:1\r\n" % (
                    self._format_descriptor(descriptor), index,
                )).encode("ascii"))
                fields = self._fields(self._line(), minimum=4)
                try:
                    if self.directory_length_field >= len(fields):
                        raise ValueError("directory length field is absent")
                    length = int(fields[self.directory_length_field], self.directory_length_base)
                except ValueError as exc:
                    raise QconnError("qconn directory payload length was not hexadecimal") from exc
                if length < 0 or length > MAX_DIRECTORY_RECORD_BYTES:
                    synchronized = False
                    raise QconnError("qconn directory payload length exceeded the bound")
                if length == 0:
                    break
                if length > maximum_bytes - total_bytes:
                    synchronized = False
                    raise QconnError("qconn directory aggregate byte bound exceeded")
                try:
                    payload = self.stream.read_exact(length)
                except QconnAbortError:
                    synchronized = False
                    raise
                except QconnError:
                    synchronized = False
                    raise
                total_bytes += length
                decoded_records = payload.decode("utf-8", "replace").split("\r\n")
                if decoded_records and decoded_records[-1] == "":
                    decoded_records.pop()
                if len(decoded_records) > maximum_records - raw_records:
                    raise QconnError("qconn directory raw record bound exceeded")
                raw_records += len(decoded_records)
                previous_entries = len(entries)
                for raw in decoded_records:
                    if raw and raw not in (".", ".."):
                        entries.append(raw)
                        if len(entries) >= maximum_entries:
                            break
                if len(entries) == previous_entries:
                    nonprogress_records += max(1, len(decoded_records))
                    if nonprogress_records > maximum_nonprogress_records:
                        raise QconnError(
                            "qconn directory nonprogress record bound exceeded"
                        )
                else:
                    nonprogress_records = 0
                index += 1
        except BaseException as exc:
            failure = exc
        if synchronized:
            try:
                self.close_descriptor(descriptor)
            except BaseException as exc:
                # A deadline/Stop on close is terminal even if an ordinary
                # protocol error was already being handled.  Conversely, no
                # cleanup failure may replace an abort already in flight.
                if isinstance(exc, QconnAbortError):
                    failure = exc
                elif failure is None:
                    failure = exc
        if failure is not None:
            raise failure.with_traceback(failure.__traceback__)
        return entries

    def download(self, path: str, output: BinaryIO, maximum_bytes: int) -> Dict[str, Any]:
        path = validate_remote_path(path)
        if maximum_bytes < 1:
            raise QconnError("qconn maximum_bytes must be positive")
        descriptor = self.open_readonly(path, directory=False)
        total = 0
        digest = hashlib.sha256()
        metadata: Dict[str, Any] = {}
        failure: Optional[BaseException] = None
        try:
            metadata = (dict(self.open_metadata[descriptor])
                        if self.metadata_source == "open_response"
                        else self.stat(descriptor))
            mode = int(metadata.get("mode", 0))
            if (mode & S_IFMT) != S_IFREG:
                raise QconnError("qconn target is not a regular file")
            advertised_size = int(metadata.get("size", -1))
            if advertised_size < 0 or advertised_size > maximum_bytes:
                raise QconnError("qconn target exceeds maximum_bytes")
            while total < maximum_bytes:
                chunk = self.read_chunk(descriptor, total, min(self.max_read_chunk, maximum_bytes - total))
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                total += len(chunk)
            if total != advertised_size:
                raise QconnError(
                    "qconn file length mismatch: expected %d, received %d" % (advertised_size, total)
                )
        except BaseException as exc:
            failure = exc
        try:
            self.close_descriptor(descriptor)
        except BaseException as exc:
            if isinstance(exc, QconnAbortError):
                failure = exc
            elif failure is None and total == 0:
                # Preserve the historical tolerance for a close reply lost
                # only after a complete nonempty transfer.
                failure = exc
        if failure is not None:
            raise failure.with_traceback(failure.__traceback__)
        return {
            "remote_path": path,
            "bytes": total,
            "sha256": digest.hexdigest(),
            "stat": metadata,
        }

    def disconnect(self) -> None:
        try:
            if self.handshake:
                self._write(b"q")
        except QconnAbortError:
            raise
        except QconnError:
            pass
        finally:
            self.stream.close()
