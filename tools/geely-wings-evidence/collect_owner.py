#!/usr/bin/env python3
"""Collect Android display ownership metadata over an existing ADB connection.

No mode changes, service restarts, root requests or QNX driver calls.
Optional --capture-id is a screencap ID, NOT an assumed Android display ID.
Optional --package collects the explicitly selected installed APK and splits.
Requires Python 3.8+ and an already installed adb. Vehicle use remains unverified.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=Path.cwd())
    parser.add_argument("--label", default="metadata")
    parser.add_argument("--capture-id", help="Numeric ID supported by this device's screencap")
    parser.add_argument("--package", action="append", default=[])
    args = parser.parse_args()
    if args.capture_id is not None and not re.fullmatch(r"[0-9]+", args.capture_id):
        parser.error("--capture-id must be numeric; inspect screencap help and display metadata first")
    if any(not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", p) for p in args.package):
        parser.error("Invalid package name")
    adb = shutil.which(args.adb)
    if not adb:
        parser.error("adb not found; specify an existing executable with --adb")
    args.out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out = args.out / ("KX11-Wings-Owner-" + stamp)
    out.mkdir()
    records = []
    manifest = dict(version=1, label=args.label, started_utc=stamp, records=records,
                    field_validation="NOT_PERFORMED", pixel_boundary="NOT_ESTABLISHED",
                    limitation="Framework screenshots may omit hardware overlays. Metadata and APK ownership do not prove who draws physical DIM wings.")

    def save():
        (out / "RESULT.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    def run(name, argv, timeout=25):
        start = time.time()
        item = dict(name=name, argv=argv, start_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
        try:
            result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
            stdout, stderr = result.stdout, result.stderr
            item.update(status="ok" if result.returncode == 0 else "command_failed", exit_code=result.returncode)
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            item.update(status="timeout", timeout_seconds=timeout)
        except OSError as error:
            stdout, stderr = b"", str(error).encode()
            item.update(status="unavailable")
        item["elapsed_seconds"] = round(time.time() - start, 3)
        (out / (name + ".out")).write_bytes(stdout)
        (out / (name + ".err")).write_bytes(stderr)
        item.update(stdout_bytes=len(stdout), stdout_sha256=hashlib.sha256(stdout).hexdigest(),
                    stderr_bytes=len(stderr), stderr_sha256=hashlib.sha256(stderr).hexdigest())
        records.append(item)
        save()
        return stdout, item

    try:
        raw, item = run("adb_devices", [adb, "devices"])
        ready = [line.split()[0] for line in raw.decode(errors="replace").splitlines()
                 if len(line.split()) == 2 and line.split()[1] == "device"]
        serial = args.serial
        if item["status"] != "ok" or (serial and serial not in ready) or (not serial and len(ready) != 1):
            manifest.update(status="BLOCKED_DEVICE_SELECTION", available_ready_devices=len(ready))
            save()
            print(out)
            return 3
        serial = serial or ready[0]
        base = [adb, "-s", serial]
        manifest["serial"] = serial
        commands = [
            ("clock_before", ["shell", "date", "+%s"]),
            ("uptime_before", ["shell", "cat", "/proc/uptime"]),
            ("boot_id", ["shell", "cat", "/proc/sys/kernel/random/boot_id"]),
            ("build_fingerprint", ["shell", "getprop", "ro.build.fingerprint"]),
            ("display", ["shell", "dumpsys", "display"]),
            ("surfaceflinger", ["shell", "dumpsys", "SurfaceFlinger"]),
            ("surfaceflinger_layers", ["shell", "dumpsys", "SurfaceFlinger", "--list"]),
            ("windows", ["shell", "dumpsys", "window", "windows"]),
            ("activities", ["shell", "dumpsys", "activity", "activities"]),
            ("processes", ["shell", "ps", "-A"]),
            ("screencap_help", ["shell", "screencap", "-h"]),
        ]
        for name, argv in commands:
            run(name, base + argv)
        if args.capture_id is not None:
            raw, item = run("framework_capture", base + ["exec-out", "screencap", "-p", "-d", args.capture_id])
            if item["status"] == "ok" and raw[:8] == b"\x89PNG\r\n\x1a\n" and len(raw) >= 24:
                width, height = struct.unpack_from(">2I", raw, 16)
                (out / "framework_capture.png").write_bytes(raw)
                item.update(png_width=width, png_height=height, capture_id=args.capture_id,
                            completeness="NOT_PROOF_OF_FINAL_HWC_OUTPUT")
            else:
                item["capture_result"] = "NO_VALID_PNG_HEADER"
            save()
        for pkg in args.package:
            prefix = "package_" + pkg
            run(prefix + "_metadata", base + ["shell", "dumpsys", "package", pkg])
            raw, item = run(prefix + "_paths", base + ["shell", "pm", "path", pkg])
            if item["status"] != "ok":
                continue
            paths = [line[len("package:"):] for line in raw.decode(errors="replace").splitlines()
                     if line.startswith("package:")]
            for number, remote in enumerate(paths):
                if not re.fullmatch(r"/[A-Za-z0-9_./+@=\-]+\.apk", remote) or "/../" in remote:
                    records.append(dict(name=prefix + "_pull", status="UNSUPPORTED_APK_PATH"))
                    save()
                    continue
                target = out / (prefix + "_" + str(number) + ".apk")
                _, pull = run(prefix + "_pull_" + str(number), base + ["pull", remote, str(target)], timeout=60)
                if target.is_file():
                    digest = hashlib.sha256()
                    with target.open("rb") as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b""):
                            digest.update(block)
                    pull.update(file=target.name, bytes=target.stat().st_size, sha256=digest.hexdigest(),
                                copy_complete=pull["status"] == "ok")
                    save()
        run("clock_after", base + ["shell", "date", "+%s"])
        run("uptime_after", base + ["shell", "cat", "/proc/uptime"])
        manifest["status"] = "COMMANDS_COMPLETED" if all(r["status"] == "ok" for r in records) else "PARTIAL"
        if any(r.get("capture_result") == "NO_VALID_PNG_HEADER" for r in records):
            manifest["status"] = "PARTIAL"
    except KeyboardInterrupt:
        manifest["status"] = "INTERRUPTED"
    finally:
        save()
    print(out)
    return 0 if manifest.get("status") == "COMMANDS_COMPLETED" else 3


if __name__ == "__main__":
    sys.exit(main())
