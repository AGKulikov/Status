#!/usr/bin/env python3
"""Stage, sign, or verify the Navigator 3.5.8 turn update from signed 3.5.7."""
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
    from .navigation_turn_candidate import check_manifest
    from .sign_navigation_update_candidate import identity
    from .sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from .verify_kx11_navigation_pair import verify_signer, verify_zipalign
else:
    from navigation_turn_candidate import check_manifest
    from sign_navigation_update_candidate import identity
    from sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
    from verify_kx11_navigation_pair import verify_signer, verify_zipalign

BASE_SHA = "58c440c2cf3e65b36ede8e0491b087e63559591407f9d8bc5540e3fa8d7346cc"
OLD_CODE, NEW_CODE = 739564638, 739564639
OLD_NAME, NEW_NAME = "30.3.0 Natro 3.5.7", "30.3.0 Natro 3.5.8"
VERSIONS = dict(old_code=OLD_CODE, new_code=NEW_CODE, old_name=OLD_NAME, new_name=NEW_NAME)
MANIFEST = "AndroidManifest.xml"
UNSIGNED = "Navigator-30.3.0-Natro-3.5.8-unsigned.apk"
SIGNED = "Navigator-30.3.0-Natro-3.5.8-signed.apk"
METADATA = "navigator-candidate.json"
REPORT = "navigator-release-report.json"


def check_payload(apk, expected):
    if payload_hashes(apk) != expected:
        raise ValueError("Navigator differs from the tested bridge or pinned baseline")


def save_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def publish(files, directory):
    # Each file is complete before it becomes visible. An identical rerun can complete a
    # partial publication; an existing artifact from a different candidate cannot be replaced.
    for src in files:
        dst = directory / src.name
        try:
            os.link(src, dst)
        except FileExistsError:
            if sha256(src) != sha256(dst):
                raise ValueError("Existing output differs: " + dst.name)


def provenance(args):
    if (not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (args.source_commit, args.source_tree))
            or not re.fullmatch(r"[1-9][0-9]*", args.run)):
        raise ValueError("Invalid packaging source or CI run")
    return dict(sourceCommit=args.source_commit, sourceTree=args.source_tree, ciRunId=args.run,
                package="ru.yandex.yandexnavi",
                versionName=NEW_NAME, versionCode=NEW_CODE, previousVersionName=OLD_NAME,
                previousVersionCode=OLD_CODE, previousSha256=BASE_SHA)


def process(args):
    record = provenance(args)
    sdk, baseline = args.sdk, args.baseline
    if sha256(baseline) != BASE_SHA:
        raise ValueError("Exact signed Navigator 3.5.7 baseline required")
    signer, align, aapt = (sdk / name for name in ("apksigner", "zipalign", "aapt"))
    previous_signature = verify_signer(signer, baseline, ("v2", "v3"))
    expected = payload_hashes(baseline)
    with zipfile.ZipFile(baseline) as archive:
        replacement = identity(archive.read(MANIFEST), **VERSIONS)
    bridge = (args.candidate / "classes19.dex").read_bytes()
    if bridge[:8] not in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"):
        raise ValueError("Android 9 compatible bridge required")
    expected[MANIFEST] = hashlib.sha256(replacement).hexdigest()
    expected["classes19.dex"] = hashlib.sha256(bridge).hexdigest()
    record.update(changedPayload=[MANIFEST, "classes19.dex"],
                  changedEntryHashes={name: expected[name] for name in (MANIFEST, "classes19.dex")},
                  unchangedPayloadEntries=len(expected) - 2, ciBridgeSha256=expected["classes19.dex"],
                  manifestSemanticsOnlyVersionChanged=True)
    candidate = args.candidate
    unsigned = candidate / UNSIGNED
    if args.action == "stage":
        root = Path(__file__).resolve().parents[1]
        actual = run("git", "-C", root, "rev-parse", "HEAD", "HEAD^{tree}").stdout.splitlines()
        if actual != [args.source_commit, args.source_tree]:
            raise ValueError("Packaging checkout differs from the supplied commit/tree")
        candidate.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=candidate) as directory:
            work = Path(directory)
            raw = work / "unaligned.apk"
            with zipfile.ZipFile(baseline) as src, zipfile.ZipFile(raw, "w") as dst:
                dst.comment = src.comment
                for entry in src.infolist():
                    if not signature_entry(entry.filename):
                        dst.writestr(copy.copy(entry), {MANIFEST: replacement, "classes19.dex": bridge}.get(entry.filename, src.read(entry)))
            staged = work / UNSIGNED
            run(align, "-f", "-P", "16", "4", raw, staged)
            verify_zipalign(align, staged)
            check_manifest(aapt, baseline, staged, **VERSIONS)
            check_payload(staged, expected)
            record.update(unsignedFile=UNSIGNED, unsignedSha256=sha256(staged))
            save_json(work / METADATA, record)
            publish([staged, work / METADATA], candidate)
        return record

    record.update(unsignedFile=UNSIGNED, unsignedSha256=sha256(unsigned))
    if json.loads((candidate / METADATA).read_text()) != record:
        raise ValueError("Navigator candidate metadata or unsigned APK differs from CI")
    check_payload(unsigned, expected)
    verify_zipalign(align, unsigned)
    check_manifest(aapt, baseline, unsigned, **VERSIONS)
    if args.action == "sign":
        args.output.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=args.output) as directory:
            work = Path(directory)
            signed = work / SIGNED
            run(signer, "sign", "--ks", args.keystore, "--ks-key-alias", "status-widget-ha",
                "--ks-pass", "file:" + str(args.password_file), "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "false", "--out", signed, unsigned)
            result, signature = signed_report(signed, signer, align, expected, record)
            evidence = work / "navigator-signature-verification.txt"
            evidence.write_text(signature + "\nPrevious Navigator:\n" + previous_signature, encoding="utf-8")
            save_json(work / REPORT, result)
            publish([signed, evidence, work / REPORT], args.output)
        return result
    result, _ = signed_report(args.output / SIGNED, signer, align, expected, record)
    if json.loads((args.output / REPORT).read_text()) != result:
        raise ValueError("Signed Navigator differs from release report")
    return result


def signed_report(signed, signer, align, expected, record):
    verification = verify_signer(signer, signed, ("v2", "v3"))
    run(signer, "verify", "--min-sdk-version", "28", "--max-sdk-version", "28", signed)
    verify_zipalign(align, signed)
    check_payload(signed, expected)
    return dict(record, apk=SIGNED, sha256=sha256(signed), size=signed.stat().st_size,
                certificateSha256=CERT, signatureV2=True, signatureV3=True, exactApi28=True,
                zipalign16KiB=True, physicalKx11Verification="pending"), verification


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action in ("stage", "sign", "verify"):
        command = commands.add_parser(action)
        paths = ["baseline", "sdk", "candidate"]
        if action != "stage":
            paths.append("output")
        if action == "sign":
            paths.extend(["keystore", "password-file"])
        for name in paths:
            command.add_argument("--" + name, type=Path, required=True)
        for name in ("source-commit", "source-tree", "run"):
            command.add_argument("--" + name, required=True)
    print(json.dumps(process(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
