#!/usr/bin/env python3
"""Build and verify the Navigator 3.5.1 candidate, or sign the exact staged CI APK offline."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile

if __package__:
    from .build_alice_focus_tool import ROOT, library_classpath
    from .sign_navigation_update_candidate import identity
    from .sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from .verify_kx11_navigation_pair import verify_maneuver_artwork, verify_signer, verify_zipalign
else:
    from build_alice_focus_tool import ROOT, library_classpath
    from sign_navigation_update_candidate import identity
    from sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from verify_kx11_navigation_pair import verify_maneuver_artwork, verify_signer, verify_zipalign

BASE_SHA = "c2524e28f711260d9ed9d4ac563a4f42c69757247f2b669022aad30171f901c0"
OLD_NAME = "30.3.0 Natro 3.4.9"
OLD_CODE = 739564631
NEW_NAME = "30.3.0 Natro 3.5.1"
NEW_CODE = 739564632
CHANGES = ["AndroidManifest.xml", "classes19.dex", "classes3.dex"]
APK = "Navigator-30.3.0-Natro-3.5.1"


def changed_hashes(before, after):
    if before.keys() != after.keys():
        raise ValueError("Navigator payload entries were added or removed")
    changed = {name: digest for name, digest in sorted(after.items()) if before[name] != digest}
    if list(changed) != CHANGES:
        raise ValueError("Unexpected Navigator payload changes: " + repr(list(changed)))
    return changed


def check_manifest(aapt, baseline, updated):
    before = run(aapt, "dump", "badging", baseline).stdout
    old = f"name='ru.yandex.yandexnavi' versionCode='{OLD_CODE}' versionName='{OLD_NAME}'"
    new = f"name='ru.yandex.yandexnavi' versionCode='{NEW_CODE}' versionName='{NEW_NAME}'"
    if before.count(old) != 1 or run(aapt, "dump", "badging", updated).stdout != before.replace(old, new):
        raise ValueError("Navigator identity, application label, SDK or ABI differs from the reviewed update")
    def tree_without_versions(apk):
        tree = run(aapt, "dump", "xmltree", apk, "AndroidManifest.xml").stdout
        return [line for line in tree.splitlines() if not re.search(r"android:version(?:Code|Name)\(", line)]
    if tree_without_versions(baseline) != tree_without_versions(updated):
        raise ValueError("Navigator manifest changed outside version attributes")


def stage_turn_pair(baseline, bridge, candidate):
    if sha256(baseline) != BASE_SHA:
        raise ValueError("Navigator 3.5.1 requires the exact signed 3.4.9 APK")
    sdk = candidate / "tools"
    verify_signer(sdk / "apksigner", baseline, ("v2", "v3"))
    before = payload_hashes(baseline)
    bridge_bytes = bridge.read_bytes()
    if bridge_bytes[:8] not in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"):
        raise ValueError("classes19.dex must be compatible with Android 9")
    tool = ROOT / "build/navigation-turn-tool"
    cp = library_classpath(tool)
    run("javac", "-encoding", "UTF-8", "-cp", cp, "-d", tool, ROOT / "tools/navigation/PatchTurnFactory.java")
    java = ("java", "-cp", str(tool) + os.pathsep + cp, "PatchTurnFactory")
    run(*java, "verify-bridge", bridge)
    staged = candidate / (APK + "-unsigned.apk")
    if staged.exists():
        raise FileExistsError(staged)
    with tempfile.TemporaryDirectory(dir=candidate) as temp:
        work = Path(temp)
        original = work / "original.dex"
        patched = work / "patched.dex"
        raw_apk = work / "unaligned.apk"
        with zipfile.ZipFile(baseline) as src, zipfile.ZipFile(raw_apk, "w") as dst:
            original.write_bytes(src.read("classes3.dex"))
            run(*java, original, patched)
            replacements = {
                "AndroidManifest.xml": identity(src.read("AndroidManifest.xml"), old_code=OLD_CODE,
                                                old_name=OLD_NAME, new_code=NEW_CODE, new_name=NEW_NAME),
                "classes3.dex": patched.read_bytes(),
                "classes19.dex": bridge_bytes,
            }
            dst.comment = src.comment
            for entry in src.infolist():
                if not signature_entry(entry.filename):
                    data = replacements[entry.filename] if entry.filename in replacements else src.read(entry)
                    dst.writestr(copy.copy(entry), data)
        aligned = work / "aligned.apk"
        run(sdk / "zipalign", "-f", "-P", "16", "4", raw_apk, aligned)
        verify_zipalign(sdk / "zipalign", aligned)
        check_manifest(sdk / "aapt", baseline, aligned)
        verify_maneuver_artwork(aligned)
        after = payload_hashes(aligned)
        changed = changed_hashes(before, after)
        if changed != {name: hashlib.sha256(data).hexdigest() for name, data in replacements.items()}:
            raise ValueError("Packed Navigator differs from the tested bridge, hook or manifest")
        aligned.rename(staged)
    return {
        "pairRequired": True, "compatiblePair": None, "baselineVersion": OLD_NAME, "baselineSha256": BASE_SHA,
        "versionName": NEW_NAME, "versionCode": NEW_CODE, "allowedPayloadChanges": CHANGES,
        "patch": "classes19.dex", "patchSha256": changed["classes19.dex"],
        "changedEntryHashes": changed, "unchangedPayloadEntries": len(after) - len(changed),
        "unsignedFile": staged.name, "unsignedSha256": sha256(staged),
    }


def sign_candidate(args):
    manifest = json.loads((args.candidate / "candidate.json").read_text())
    expected = {"sourceCommit": args.source_commit, "sourceTree": args.source_tree,
                "versionName": "3.5.1", "versionCode": 208021384}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("Wrong candidate source or release")
    nav = manifest["navigator"]
    unsigned = args.candidate / (APK + "-unsigned.apk")
    if (nav.get("pairRequired") is not True or nav.get("allowedPayloadChanges") != CHANGES or
            nav.get("baselineSha256") != BASE_SHA or sha256(args.baseline) != BASE_SHA or
            nav.get("versionName") != NEW_NAME or nav.get("versionCode") != NEW_CODE or
            nav.get("unsignedFile") != unsigned.name or sha256(unsigned) != nav.get("unsignedSha256")):
        raise ValueError("Wrong Navigator candidate, baseline or payload boundary")
    sdk = args.candidate / "tools"
    signer, align, aapt = (sdk / name for name in ("apksigner", "zipalign", "aapt"))
    for executable in (signer, align, aapt):
        executable.chmod(0o755)
    before_sign = verify_signer(signer, args.baseline, ("v2", "v3"))
    before, tested = payload_hashes(args.baseline), payload_hashes(unsigned)
    changed = changed_hashes(before, tested)
    if (changed != nav.get("changedEntryHashes") or sha256(args.candidate / "classes19.dex") != changed["classes19.dex"]
            or changed["classes19.dex"] != nav.get("patchSha256")):
        raise ValueError("Navigator differs from the tested CI payload")
    args.output.mkdir(parents=True, exist_ok=False)
    signed = args.output / (APK + "-signed.apk")
    with tempfile.TemporaryDirectory(dir=args.output) as temp:
        verified = Path(temp) / signed.name
        run(signer, "sign", "--ks", args.keystore, "--ks-key-alias", "status-widget-ha",
            "--ks-pass", "file:" + str(args.password_file), "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--out", verified, unsigned)
        signature = verify_signer(signer, verified, ("v2", "v3"))
        run(signer, "verify", "--min-sdk-version", "28", "--max-sdk-version", "28", verified)
        verify_zipalign(align, verified)
        check_manifest(aapt, args.baseline, verified)
        verify_maneuver_artwork(verified)
        if payload_hashes(verified) != tested:
            raise ValueError("Signing changed the tested Navigator payload")
        verified.rename(signed)
    report = {
        "sourceCommit": args.source_commit, "sourceTree": args.source_tree, "ciRunId": manifest["ciRunId"],
        "apk": signed.name, "package": "ru.yandex.yandexnavi", "versionName": NEW_NAME, "versionCode": NEW_CODE,
        "previousVersionCode": OLD_CODE, "previousSha256": BASE_SHA, "sha256": sha256(signed), "size": signed.stat().st_size,
        "changedPayload": CHANGES, "changedEntryHashes": changed, "unchangedPayloadEntries": len(tested) - len(changed),
        "manifestSemanticsOnlyVersionChanged": True, "ciBridgeSha256": changed["classes19.dex"],
        "certificateSha256": CERT, "signatureV2": True, "signatureV3": True, "exactApi28": True,
        "zipalign16KiB": True, "physicalKx11Verification": "pending",
    }
    (args.output / "navigator-signature-verification.txt").write_text(signature + "\nPrevious Navigator:\n" + before_sign)
    (args.output / "navigator-release-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("candidate", "baseline", "keystore", "password-file", "output"):
        parser.add_argument("--" + arg, type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-tree", required=True)
    sign_candidate(parser.parse_args())
