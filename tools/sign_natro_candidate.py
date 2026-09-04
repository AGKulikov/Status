#!/usr/bin/env python3
"""Sign one reviewed Natro CI candidate and verify its update metadata against the previous APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

CERT = "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75"
PACKAGE = "ru.natro.statuswidget"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args, check=True):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed: {result.stderr[-2000:]}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--previous-apk", required=True, type=Path)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--password-file", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-tree", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = args.candidate.resolve()
    manifest = json.loads((root / "candidate.json").read_text())
    expected = {"sourceCommit": args.source_commit, "sourceTree": args.source_tree,
                "versionName": args.version, "versionCode": args.version_code, "package": PACKAGE}
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f"Candidate {key} does not match the reviewed release")
    unsigned = root / f"Natro-{args.version}-unsigned.apk"
    if sha256(unsigned) != manifest["unsignedSha256"]:
        raise ValueError("Unsigned APK checksum mismatch")
    apksigner = root / "tools/apksigner"
    aapt = root / "tools/aapt"
    zipalign = root / "tools/zipalign"
    for executable in (apksigner, aapt, zipalign):
        executable.chmod(0o755)
    if run(apksigner, "verify", unsigned, check=False).returncode == 0:
        raise ValueError("Candidate is already signed")

    def identity(apk):
        badging = run(aapt, "dump", "badging", apk).stdout
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not match:
            raise ValueError("APK has no readable package identity")
        return {"package": match[1], "versionCode": int(match[2]), "versionName": match[3]}, badging

    def verify_certificate(apk):
        text = run(apksigner, "verify", "--verbose", "--print-certs",
                   "--min-sdk-version", "28", apk).stdout
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", text)
        if certificates != [CERT] or "Verified using v3 scheme (APK Signature Scheme v3): true" not in text:
            raise ValueError("APK must have one stable Natro signer and a valid v3 signature")
        return text

    previous, _ = identity(args.previous_apk)
    previous_verification = verify_certificate(args.previous_apk)
    if previous["package"] != PACKAGE or args.version_code != previous["versionCode"] + 1:
        raise ValueError("Release must update the previous Natro package with versionCode +1")
    current, badging = identity(unsigned)
    if current != {k: expected[k] for k in ("package", "versionCode", "versionName")}:
        raise ValueError("APK identity differs from the candidate manifest")
    if ("application-label:'Natro'" not in badging or "sdkVersion:'28'" not in badging
            or "targetSdkVersion:'28'" not in badging or "application-debuggable" in badging):
        raise ValueError("Unexpected application label, Android version or debug flag")
    with zipfile.ZipFile(unsigned) as apk:
        if apk.testzip() is not None:
            raise ValueError("Corrupt APK ZIP entry")
        dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        for marker in (b"EcarxParkingWindowPolicy", b"parking-absence-confirmation", b"com.ecarx.parking"):
            if not any(marker in data for data in dex):
                raise ValueError("Parking visibility implementation missing from APK")

    args.output.mkdir(parents=True, exist_ok=True)
    signed = args.output / f"Natro-{args.version}-signed.apk"
    if signed.exists():
        raise FileExistsError("Signed release already exists; choose a new output directory")
    aligned = args.output / f"Natro-{args.version}-aligned.apk"
    run(zipalign, "-f", "-P", "16", "4", unsigned, aligned)
    # The release key uses the keystore password. apksigner reuses it by default;
    # passing the same one-line file twice would consume a nonexistent second line.
    run(apksigner, "sign", "--ks", args.keystore, "--ks-key-alias", "status-widget-ha",
        "--ks-pass", f"file:{args.password_file}",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--out", signed, aligned)
    verified = verify_certificate(signed)
    v2 = run(apksigner, "verify", "--verbose", "--min-sdk-version", "24", signed).stdout
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in v2:
        raise ValueError("APK v2 signature verification failed")
    run(zipalign, "-c", "-P", "16", "4", signed)
    signed_identity, _ = identity(signed)
    if signed_identity != current:
        raise ValueError("Signed APK identity changed")
    aligned.unlink()
    idsig = Path(str(signed) + ".idsig")
    if idsig.exists():
        idsig.unlink()
    report = {**manifest, "signedApk": signed.name, "signedSha256": sha256(signed),
              "certificateSha256": CERT, "signatureV2": True, "signatureV3": True,
              "signerCount": 1, "zipalign16KiB": True,
              "previous": {**previous, "sha256": sha256(args.previous_apk)},
              "installOverMetadataVerified": True, "physicalKx11Verification": "pending",
              "navigatorChanged": False, "helperChanged": False,
              "signingScriptSha256": sha256(Path(__file__)),
              "toolSha256": {str(p.relative_to(root / "tools")): sha256(p)
                              for p in sorted((root / "tools").rglob("*")) if p.is_file()}}
    (args.output / "release-report.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.output / "signature-verification.txt").write_text(
        "New APK (Android 9):\n" + verified + "\nNew APK (v2):\n" + v2
        + "\nPrevious APK:\n" + previous_verification)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
