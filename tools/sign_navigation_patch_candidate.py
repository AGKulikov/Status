#!/usr/bin/env python3
"""Replace only the CI-built bridge DEX in a pinned private Navigator APK and sign it."""
import argparse
import copy
import json
from pathlib import Path
import re
import zipfile

try:
    from .sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from .verify_kx11_navigation_pair import verify_signer, verify_zipalign, verify_maneuver_artwork
except ImportError:
    from sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from verify_kx11_navigation_pair import verify_signer, verify_zipalign, verify_maneuver_artwork

BASELINE_SHA256 = "b224bed5268469f620ab4bff3fec382aece868b9372450ff05f1f9176631280a"


def replace_bridge(baseline, patch, output):
    """Keep every payload entry and its ZIP metadata; discard only obsolete JAR signatures."""
    before = payload_hashes(baseline)
    if "classes19.dex" not in before:
        raise ValueError("Pinned baseline has no bridge DEX")
    dex = patch.read_bytes()
    if dex[:4] != b"dex\n" or dex[7:8] != b"\0" or not dex[4:7].isdigit() or int(dex[4:7]) > 39:
        raise ValueError("Bridge must be a DEX compatible with API 28")
    with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(output, "w") as target:
        target.comment = source.comment
        for entry in source.infolist():
            if signature_entry(entry.filename):
                continue
            target.writestr(copy.copy(entry), dex if entry.filename == "classes19.dex" else source.read(entry))
    expected = dict(before)
    expected["classes19.dex"] = sha256(patch)
    if payload_hashes(output) != expected:
        raise ValueError("Repacked Navigator payload differs outside the bridge")
    return before, expected


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--candidate", required=True, type=Path)
    p.add_argument("--baseline", required=True, type=Path)
    p.add_argument("--source-commit", required=True)
    p.add_argument("--source-tree", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--keystore", required=True, type=Path)
    p.add_argument("--password-file", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate / "candidate.json").read_text())
    for key, value in (("sourceCommit", args.source_commit), ("sourceTree", args.source_tree),
                       ("versionName", args.version)):
        if manifest.get(key) != value:
            raise ValueError("Candidate source identity mismatch: " + key)
    nav = manifest["navigator"]
    if not nav.get("pairRequired") or nav.get("allowedPayloadChanges") != ["classes19.dex"]:
        raise ValueError("This candidate does not authorize one bridge replacement")
    if nav.get("baselineSha256") != BASELINE_SHA256 or sha256(args.baseline) != BASELINE_SHA256:
        raise ValueError("Navigator baseline is not the reviewed 2.9.8 APK")
    patch = candidate / "classes19.dex"
    if sha256(patch) != nav["patchSha256"]:
        raise ValueError("CI bridge checksum mismatch")
    sdk = candidate / "tools"
    for name in ("aapt", "apksigner", "zipalign"):
        (sdk / name).chmod(0o755)
    aapt, signer, align = (sdk / n for n in ("aapt", "apksigner", "zipalign"))
    verify_signer(signer, args.baseline, ("v2", "v3"))
    baseline_badging = run(aapt, "dump", "badging", args.baseline).stdout
    if not re.search(r"package: name='ru.yandex.yandexnavi' versionCode='739564630' versionName='30.3.0'", baseline_badging):
        raise ValueError("Unexpected Navigator identity")
    args.output.mkdir(parents=True, exist_ok=True)
    signed = args.output / ("Navigator-30.3.0-Natro-" + args.version + "-signed.apk")
    unsigned = args.output / "navigator-bridge-unsigned.apk"
    aligned = args.output / "navigator-bridge-aligned.apk"
    if any(path.exists() for path in (signed, unsigned, aligned)):
        raise FileExistsError("Output already exists")
    before, expected = replace_bridge(args.baseline, patch, unsigned)
    run(align, "-f", "-P", "16", "4", unsigned, aligned)
    run(signer, "sign", "--ks", args.keystore, "--ks-key-alias", "status-widget-ha",
        "--ks-pass", "file:" + str(args.password_file), "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--out", signed, aligned)
    verification = verify_signer(signer, signed, ("v2", "v3"))
    verify_zipalign(align, signed)
    verify_maneuver_artwork(signed)
    if run(aapt, "dump", "badging", signed).stdout != baseline_badging:
        raise ValueError("Navigator identity or resources changed")
    after = payload_hashes(signed)
    if after != expected:
        raise ValueError("Signed Navigator differs from the reviewed payload")
    changed = sorted(name for name in before if before[name] != after[name])
    if changed != ["classes19.dex"]:
        raise ValueError("Exactly one bridge DEX must change")
    report = {
        "sourceCommit": args.source_commit, "sourceTree": args.source_tree,
        "ciRunId": manifest["ciRunId"], "pairVersion": args.version,
        "apk": signed.name, "sha256": sha256(signed), "size": signed.stat().st_size,
        "package": "ru.yandex.yandexnavi", "versionName": "30.3.0", "versionCode": 739564630,
        "baselineSha256": BASELINE_SHA256, "patchSha256": sha256(patch),
        "changedPayloadEntries": changed, "unchangedPayloadEntries": len(after) - len(changed),
        "manifestResourcesAndNativeLibrariesUnchanged": True,
        "certificateSha256": CERT, "signatureV2": True, "signatureV3": True,
        "zipalign16KiB": True, "physicalKx11Verification": "pending",
        "signingScriptSha256": sha256(Path(__file__)),
    }
    (args.output / "navigator-release-report.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.output / "navigator-signature-verification.txt").write_text(verification)
    unsigned.unlink()
    aligned.unlink()
    Path(str(signed) + ".idsig").unlink(missing_ok=True)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

