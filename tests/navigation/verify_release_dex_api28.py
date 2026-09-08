#!/usr/bin/env python3
"""Verify the exact released bridge DEX files with ART on an API 28 CI emulator.

This never installs an APK and refuses physical devices. A successful result is
DEX verification on the recorded emulator, not install-over acceptance on KX11.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


RELEASES = {
    "2.8.1": (269132, "b2a1ef4c28f47ac04986a39a7077c2019bae37f784c0f82a299ed8de9f76140a"),
    "2.8.2": (272968, "7c4071a47f9313b2a59afe357d1447a7572d0df6f84152a4e1f83327aee76206"),
}


def run(argv, timeout=30):
    return subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-dex", type=Path, required=True)
    parser.add_argument("--new-dex", type=Path, required=True)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    report = {"scope": "exact released DEX files, API 28 emulator ART verifier",
              "apkBuilt": False, "apkInstalled": False,
              "kx11InstallOverVerified": False, "releases": {}, "success": False}
    adb = [args.adb, "-s", args.serial]
    try:
        if not args.serial.startswith("emulator-"):
            raise RuntimeError("This diagnostic is restricted to a CI emulator")
        for version, path in [("2.8.1", args.old_dex), ("2.8.2", args.new_dex)]:
            data = path.read_bytes()
            sha = hashlib.sha256(data).hexdigest()
            report["releases"][version] = {"bytes": len(data), "sha256": sha}
            if (len(data), sha) != RELEASES[version]:
                raise RuntimeError(f"{version}: DEX does not match the actual released APK")

        deadline = time.monotonic() + 480
        while time.monotonic() < deadline:
            try:
                boot = run(adb + ["shell", "getprop", "sys.boot_completed"], timeout=10)
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(3)
        else:
            raise RuntimeError("API 28 emulator did not finish booting in 480 seconds")

        properties = {}
        for name in ["ro.kernel.qemu", "ro.build.version.sdk", "ro.build.fingerprint",
                     "ro.product.cpu.abilist"]:
            result = run(adb + ["shell", "getprop", name])
            if result.returncode:
                raise RuntimeError(f"Cannot read emulator property {name}")
            properties[name] = result.stdout.strip()
        report["emulator"] = properties
        if properties["ro.kernel.qemu"] != "1" or properties["ro.build.version.sdk"] != "28":
            raise RuntimeError("Refusing a non-emulator or non-API-28 target")
        if "x86_64" not in properties["ro.product.cpu.abilist"].split(","):
            raise RuntimeError("The CI verification environment must be x86_64")

        remote = "/data/local/tmp/natro-release-dex-check"
        mkdir = run(adb + ["shell", "mkdir", "-p", remote])
        if mkdir.returncode:
            raise RuntimeError("Cannot prepare the emulator verification directory")
        for version, path in [("2.8.1", args.old_dex), ("2.8.2", args.new_dex)]:
            dex = f"{remote}/{version}.dex"
            oat = f"{remote}/{version}.odex"
            pushed = run(adb + ["push", str(path), dex])
            if pushed.returncode:
                raise RuntimeError(f"{version}: could not transfer DEX to the emulator")
            digest = run(adb + ["shell", "sha256sum", dex])
            if digest.returncode or digest.stdout.split()[0] != RELEASES[version][1]:
                raise RuntimeError(f"{version}: emulator DEX hash mismatch")
            flags = [f"--dex-file={dex}", f"--oat-file={oat}",
                     "--instruction-set=x86_64", "--compiler-filter=verify",
                     "--abort-on-hard-verifier-error", "--runtime-arg", "-Xnorelocate"]
            checked = run(adb + ["shell", "/system/bin/dex2oat"] + flags, timeout=180)
            (args.out / f"{version}-dex2oat.txt").write_text(checked.stdout)
            entry = report["releases"][version]
            entry.update({"emulatorSha256Verified": True, "dex2oatFlags": flags,
                          "dex2oatExitCode": checked.returncode})
            header = run(adb + ["shell", "/system/bin/oatdump", f"--oat-file={oat}",
                               "--header-only"], timeout=60)
            (args.out / f"{version}-oat-header.txt").write_text(header.stdout)
            entry["oatdumpExitCode"] = header.returncode
            entry["verifiedOatProduced"] = header.returncode == 0 and "verify" in header.stdout
            entry["passed"] = checked.returncode == 0 and entry["verifiedOatProduced"]
            print(f"{version}: dex2oat={checked.returncode}; "
                  f"verification OAT={entry['verifiedOatProduced']}", flush=True)
        report["success"] = all(x.get("passed") for x in report["releases"].values())
    except Exception as failure:
        report["error"] = f"{type(failure).__name__}: {failure}"
        print(report["error"], flush=True)
    finally:
        (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
