#!/usr/bin/env python3
"""Reject Navigator patches that drift outside the reviewed 30.3.0 boundary.

The verifier compares uncompressed ZIP entry bytes, so zipalign, compression level and the APK
Signing Block may change without hiding a resource/native-library change. The exact input baseline
hash is mandatory. A release is allowed to replace only classes4.dex and add classes19.dex.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Dict, Iterable, Mapping, Sequence, Set, Tuple
import zipfile


SIGNING_ENTRY = re.compile(
    r"^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.IGNORECASE
)
DEX_ENTRY = re.compile(r"^classes(?:[2-9]|[1-9][0-9]+)?\.dex$")
CERT_DIGEST = re.compile(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", re.IGNORECASE)
SCHEME_RESULT = re.compile(
    r"Verified using (v\d+(?:\.\d+)?) scheme[^:]*:\s*(true|false)", re.IGNORECASE
)


class VerificationError(RuntimeError):
    """A release invariant was violated."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def zip_entry_digests(path: Path) -> Dict[str, str]:
    result: Dict[str, str] = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.filename in result:
                raise VerificationError(
                    f"{path.name}: duplicate ZIP entry {info.filename!r}"
                )
            if info.is_dir():
                result[info.filename] = _sha256_bytes(b"")
                continue
            try:
                result[info.filename] = _sha256_bytes(archive.read(info))
            except (OSError, RuntimeError, zipfile.BadZipFile) as error:
                raise VerificationError(
                    f"{path.name}: cannot read {info.filename!r}: {error}"
                ) from error
    return result


def _as_string_set(config: Mapping[str, object], key: str) -> Set[str]:
    raw = config.get(key, [])
    if not isinstance(raw, list) or any(not isinstance(value, str) for value in raw):
        raise VerificationError(f"configuration field {key!r} must be a string array")
    return set(raw)


def _is_signing_entry(name: str) -> bool:
    return SIGNING_ENTRY.fullmatch(name) is not None


def verify_zip_contents(
    config: Mapping[str, object], baseline: Path, candidate: Path, *, release: bool
) -> Tuple[Set[str], Set[str], int]:
    expected_baseline = str(config.get("baseline_apk_sha256", "")).lower()
    actual_baseline = sha256_file(baseline)
    if actual_baseline != expected_baseline:
        raise VerificationError(
            "wrong 30.3.0 baseline: "
            f"expected {expected_baseline}, found {actual_baseline}"
        )

    baseline_entries = zip_entry_digests(baseline)
    candidate_entries = zip_entry_digests(candidate)
    mutable = _as_string_set(config, "mutable_entries")
    allowed_new = _as_string_set(config, "allowed_new_entries")
    critical = _as_string_set(config, "critical_entries")
    critical_prefixes = _as_string_set(config, "critical_prefixes")

    absent_critical = sorted(name for name in critical if name not in baseline_entries)
    if absent_critical:
        raise VerificationError(
            "baseline configuration names missing entries: " + ", ".join(absent_critical)
        )
    for prefix in critical_prefixes:
        if not any(name.startswith(prefix) for name in baseline_entries):
            raise VerificationError(f"baseline has no entries under critical prefix {prefix!r}")

    baseline_names = set(baseline_entries)
    candidate_names = set(candidate_entries)
    missing = sorted(
        name for name in baseline_names - candidate_names if not _is_signing_entry(name)
    )
    if missing:
        raise VerificationError("candidate removed baseline entries: " + ", ".join(missing))

    unexpected_new = sorted(
        name
        for name in candidate_names - baseline_names
        if name not in allowed_new and not _is_signing_entry(name)
    )
    if unexpected_new:
        raise VerificationError("candidate added unreviewed entries: " + ", ".join(unexpected_new))

    changed = {
        name
        for name in baseline_names & candidate_names
        if baseline_entries[name] != candidate_entries[name] and not _is_signing_entry(name)
    }
    forbidden_changed = sorted(changed - mutable)
    if forbidden_changed:
        raise VerificationError(
            "candidate changed protected entries: " + ", ".join(forbidden_changed)
        )

    protected = (baseline_names - mutable) - {
        name for name in baseline_names if _is_signing_entry(name)
    }
    for name in critical:
        if candidate_entries.get(name) != baseline_entries[name]:
            raise VerificationError(f"critical entry changed: {name}")
    for prefix in critical_prefixes:
        drift = sorted(
            name
            for name in baseline_names
            if name.startswith(prefix)
            and candidate_entries.get(name) != baseline_entries[name]
        )
        if drift:
            raise VerificationError(
                f"critical prefix {prefix!r} changed: " + ", ".join(drift)
            )

    new_entries = {
        name
        for name in candidate_names - baseline_names
        if not _is_signing_entry(name)
    }
    if any(DEX_ENTRY.fullmatch(name) for name in new_entries - allowed_new):
        raise VerificationError("an unreviewed DEX was added")

    if release:
        required_changed = _as_string_set(config, "release_required_changed_entries")
        required_new = _as_string_set(config, "release_required_new_entries")
        missing_changes = sorted(required_changed - changed)
        missing_new = sorted(required_new - new_entries)
        if missing_changes:
            raise VerificationError(
                "release is missing required patched entries: " + ", ".join(missing_changes)
            )
        if missing_new:
            raise VerificationError(
                "release is missing required new entries: " + ", ".join(missing_new)
            )

    return changed, new_entries, len(protected)


def _find_apksigner(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if path.is_file() and os.access(path, os.X_OK):
            return path
        raise VerificationError(f"apksigner is not executable: {path}")

    on_path = shutil.which("apksigner")
    if on_path:
        return Path(on_path)

    candidates = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(variable)
        if root:
            candidates.extend(Path(root).glob("build-tools/*/apksigner"))
    if candidates:
        return sorted(candidates)[-1]
    raise VerificationError("apksigner was not found; pass --apksigner explicitly")


def verify_signature(
    apk: Path, apksigner: Path, expected_digest: str, required_schemes: Iterable[str]
) -> Tuple[str, Set[str]]:
    completed = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.returncode != 0:
        raise VerificationError(
            f"apksigner rejected {apk.name}:\n{completed.stdout.strip()}"
        )
    digests = {value.lower() for value in CERT_DIGEST.findall(completed.stdout)}
    expected = expected_digest.lower()
    if digests != {expected}:
        rendered = ", ".join(sorted(digests)) or "none"
        raise VerificationError(
            f"{apk.name}: expected exactly signer {expected}, found {rendered}"
        )
    enabled_schemes = {
        scheme.lower()
        for scheme, result in SCHEME_RESULT.findall(completed.stdout)
        if result.lower() == "true"
    }
    required = {scheme.lower() for scheme in required_schemes}
    absent = sorted(required - enabled_schemes)
    if absent:
        raise VerificationError(
            f"{apk.name}: required signature schemes are absent: {', '.join(absent)}"
        )
    return expected, enabled_schemes


def load_config(path: Path) -> Mapping[str, object]:
    try:
        with path.open("r", encoding="utf-8") as stream:
            config = json.load(stream)
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot load {path}: {error}") from error
    if config.get("schema") != "natro-navigation-baseline/v1":
        raise VerificationError("unsupported baseline configuration schema")
    return config


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    default_config = Path(__file__).with_name("navigation_mod_30_3_baseline.json")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--config", type=Path, default=default_config)
    parser.add_argument("--apksigner", help="path to Android SDK apksigner")
    parser.add_argument(
        "--mode",
        choices=("release", "unsigned-patch", "baseline-self-check"),
        default="release",
        help=("release requires the reviewed patch and release signature; unsigned-patch checks "
              "the same entry boundary before signing; self-check requires the exact baseline"),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        config = load_config(args.config)
        if not args.baseline.is_file() or not args.candidate.is_file():
            raise VerificationError("baseline and candidate must both be regular files")
        self_check = args.mode == "baseline-self-check"
        unsigned_patch = args.mode == "unsigned-patch"
        if self_check and sha256_file(args.candidate) != str(
            config["baseline_apk_sha256"]
        ).lower():
            raise VerificationError("baseline self-check candidate is not the exact baseline APK")

        changed, new_entries, protected_count = verify_zip_contents(
            config, args.baseline, args.candidate, release=not self_check
        )
        if unsigned_patch:
            signer = "unsigned (content gate only)"
            enabled_schemes = set()
        else:
            apksigner = _find_apksigner(args.apksigner)
            schemes = _as_string_set(config, "required_signature_schemes")
            verify_signature(
                args.baseline,
                apksigner,
                str(config["baseline_signer_sha256"]),
                schemes,
            )
            expected_candidate_signer = str(
                config["baseline_signer_sha256"]
                if self_check
                else config["release_signer_sha256"]
            )
            signer, enabled_schemes = verify_signature(
                args.candidate, apksigner, expected_candidate_signer, schemes
            )
    except (KeyError, VerificationError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1

    print("PASS: Navigator 30.3.0 baseline boundary preserved")
    print(f"  protected entries: {protected_count}")
    print(f"  changed entries: {', '.join(sorted(changed)) or 'none'}")
    print(f"  new entries: {', '.join(sorted(new_entries)) or 'none'}")
    print(f"  signer SHA-256: {signer}")
    print(f"  signature schemes: {', '.join(sorted(enabled_schemes)) or 'not checked'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
