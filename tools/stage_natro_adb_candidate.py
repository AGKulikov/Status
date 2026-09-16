#!/usr/bin/env python3
"""Stage the exact tested APK and official SDK signing tools for private offline signing."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def main():
    version = os.environ["VERSION_NAME"]
    code = int(os.environ["VERSION_CODE"])
    out = Path(os.environ["RUNNER_TEMP"]) / ("natro-" + version + "-candidate")
    out.mkdir(exist_ok=False)
    apks = list(Path("app/build/outputs/apk/geely/release").glob("*.apk"))
    if len(apks) != 1:
        raise ValueError("Expected exactly one Geely release APK")
    reports = list(Path("app/build/test-results/testGeelyDebugUnitTest").glob("TEST-*.xml"))
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
    if counts["tests"] < 1900 or counts["failures"] or counts["errors"]:
        raise ValueError("Full test suite must pass before candidate staging")
    unsigned = out / ("Natro-" + version + "-unsigned.apk")
    shutil.copy2(apks[0], unsigned)
    with zipfile.ZipFile(unsigned) as apk:
        if apk.testzip() is not None:
            raise ValueError("APK CRC failure")
        for asset in ("assets/lan/index.html", "assets/lan/client.js", "assets/lan/client.css", "assets/natro-wechat-compat.apk"):
            if not apk.read(asset):
                raise ValueError("Required asset is empty: " + asset)
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    (out / "tools/lib").mkdir(parents=True)
    for name in ("aapt", "apksigner", "zipalign", "dexdump"):
        shutil.copy2(sdk / name, out / "tools" / name)
    shutil.copy2(sdk / "lib/apksigner.jar", out / "tools/lib/apksigner.jar")
    shutil.copytree(sdk / "lib64", out / "tools/lib64")
    shutil.copytree(Path("app/build/test-results/testGeelyDebugUnitTest"), out / "test-results")
    patch = Path("build/navigation-mod/classes19.dex")
    if not patch.is_file() or patch.stat().st_size < 10000:
        raise ValueError("Compiled Navigator patch is required for this pair")
    shutil.copy2(patch, out / "classes19.dex")
    manifest = {
        "versionName": version, "versionCode": code, "package": "ru.natro.statuswidget",
        "sourceCommit": os.environ["GITHUB_SHA"],
        "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
        "ciRunId": os.environ["GITHUB_RUN_ID"], "unitTests": counts,
        "unsignedSha256": sha256(unsigned),
        "navigator": {
            "pairRequired": True, "patch": "classes19.dex", "patchSha256": sha256(patch),
            "baselineVersion": "30.3.0-Natro-2.9.8",
            "baselineSha256": "b224bed5268469f620ab4bff3fec382aece868b9372450ff05f1f9176631280a",
            "allowedPayloadChanges": ["classes19.dex"],
        },
        "physicalKx11Verification": "pending", "browserVisualVerification": "pending",
    }
    (out / "candidate.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"candidate": str(out), "unitTests": counts}))

if __name__ == "__main__":
    main()
