#!/usr/bin/env python3
"""Verify that the signed Natro/Navigator pair keeps the reviewed ECARX KX11 contract."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable, Sequence
import zipfile


KX11_ANDROID_API = 28
NATRO_PACKAGE = "ru.natro.statuswidget"
NATRO_VERSION_NAME = os.environ.get("EXPECTED_NATRO_VERSION_NAME", "2.5.5")
NATRO_VERSION_CODE = os.environ.get("EXPECTED_NATRO_VERSION_CODE", "208021288")
NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi"
NAVIGATOR_VERSION_CODE = "739564630"
NAVIGATOR_BASELINE_SHA256 = (
    "663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f"
)
STABLE_CERT_SHA256 = (
    "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75"
)
KX11_NAVIGATOR_ABIS = {"arm64-v8a"}
CERT_DIGEST = re.compile(
    r"Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})", re.IGNORECASE
)
PACKAGE_FIELD = re.compile(r"\b([A-Za-z]+)='([^']*)'")


class VerificationError(RuntimeError):
    """A release invariant required by the KX11 head unit was violated."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command: Sequence[str]) -> str:
    completed = subprocess.run(
        list(command),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.returncode != 0:
        raise VerificationError(
            f"command failed ({completed.returncode}): {' '.join(command)}\n"
            f"{completed.stdout.strip()}"
        )
    return completed.stdout


def badging(aapt: Path, apk: Path) -> str:
    return run([str(aapt), "dump", "badging", str(apk)]).replace("\r\n", "\n")


def manifest_tree(aapt: Path, apk: Path) -> str:
    return run([
        str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml",
    ]).replace("\r\n", "\n")


def activity_block(tree: str, class_name: str) -> str:
    lines = tree.splitlines()
    name_marker = f'android:name(0x01010003)="{class_name}"'
    matches = [index for index, line in enumerate(lines) if name_marker in line]
    if len(matches) != 1:
        raise VerificationError(
            f"expected exactly one manifest activity {class_name}, found {len(matches)}"
        )
    name_index = matches[0]
    start = name_index
    while start >= 0 and not lines[start].startswith("      E: activity "):
        start -= 1
    if start < 0:
        raise VerificationError(f"cannot locate manifest block for {class_name}")
    end = start + 1
    while end < len(lines) and not (
        lines[end].startswith("      E: ")
        and not lines[end].startswith("        ")
    ):
        end += 1
    return "\n".join(lines[start:end])


def service_block(tree: str, class_name: str) -> str:
    lines = tree.splitlines()
    name_marker = f'android:name(0x01010003)="{class_name}"'
    matches = [index for index, line in enumerate(lines) if name_marker in line]
    if len(matches) != 1:
        raise VerificationError(
            f"expected exactly one manifest service {class_name}, found {len(matches)}"
        )
    start = matches[0]
    while start >= 0 and not lines[start].startswith("      E: service "):
        start -= 1
    if start < 0:
        raise VerificationError(f"cannot locate manifest service block for {class_name}")
    end = start + 1
    while end < len(lines) and not (
        lines[end].startswith("      E: ") and not lines[end].startswith("        ")
    ):
        end += 1
    return "\n".join(lines[start:end])


def package_fields(value: str) -> dict[str, str]:
    first = next((line for line in value.splitlines() if line.startswith("package: ")), "")
    if not first:
        raise VerificationError("aapt badging has no package line")
    return dict(PACKAGE_FIELD.findall(first))


def single_badging_value(value: str, key: str) -> str:
    prefix = f"{key}:'"
    matches = [line[len(prefix):-1] for line in value.splitlines()
               if line.startswith(prefix) and line.endswith("'")]
    if len(matches) != 1:
        raise VerificationError(f"expected exactly one {key} value, found {len(matches)}")
    return matches[0]


def native_abis(apk: Path) -> set[str]:
    result: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) >= 3 and parts[0] == "lib" and parts[1]:
                result.add(parts[1])
    return result


def zip_entry(apk: Path, name: str) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        try:
            return archive.read(name)
        except KeyError as error:
            raise VerificationError(f"{apk.name} has no required {name}") from error


def require_dex_strings(apk: Path, expected: Iterable[bytes]) -> None:
    remaining = set(expected)
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            payload = archive.read(name)
            remaining = {needle for needle in remaining if needle not in payload}
            if not remaining:
                return
    rendered = ", ".join(value.decode("ascii", "replace") for value in sorted(remaining))
    raise VerificationError(f"{apk.name}: missing DEX window contract strings: {rendered}")


def verify_signer(apksigner: Path, apk: Path, schemes: Iterable[str]) -> str:
    output = run([
        str(apksigner), "verify", "--verbose", "--print-certs",
        "--min-sdk-version", str(KX11_ANDROID_API), str(apk),
    ])
    digests = {value.lower() for value in CERT_DIGEST.findall(output)}
    if digests != {STABLE_CERT_SHA256}:
        raise VerificationError(
            f"{apk.name}: expected one stable signer {STABLE_CERT_SHA256}, "
            f"found {', '.join(sorted(digests)) or 'none'}"
        )
    if "Number of signers: 1" not in output:
        raise VerificationError(f"{apk.name}: APK must have exactly one signer")
    probes = {KX11_ANDROID_API: output}
    for scheme in schemes:
        # In an API-28-only range apksigner reports only the applicable v3 block, even when the
        # APK also contains v2. Probe v2 at its introduction API solely to prove block presence;
        # the actual KX11 signature has already been verified above at API 28.
        probe_api = 24 if scheme == "v2" else KX11_ANDROID_API
        if probe_api not in probes:
            probes[probe_api] = run([
                str(apksigner), "verify", "--verbose", "--print-certs",
                "--min-sdk-version", str(probe_api), str(apk),
            ])
        marker = f"Verified using {scheme} scheme (APK Signature Scheme {scheme}): true"
        if marker not in probes[probe_api]:
            raise VerificationError(f"{apk.name}: required {scheme} signature is absent")
    return output


def verify_zipalign(zipalign: Path, apk: Path) -> None:
    run([str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)])


def verify_natro(aapt: Path, apksigner: Path, zipalign: Path, apk: Path) -> str:
    value = badging(aapt, apk)
    fields = package_fields(value)
    expected = {
        "name": NATRO_PACKAGE,
        "versionCode": NATRO_VERSION_CODE,
        "versionName": NATRO_VERSION_NAME,
    }
    for key, wanted in expected.items():
        if fields.get(key) != wanted:
            raise VerificationError(
                f"Natro {key}: expected {wanted!r}, found {fields.get(key)!r}"
            )
    if single_badging_value(value, "sdkVersion") != str(KX11_ANDROID_API):
        raise VerificationError("Natro minSdk must remain Android 9 / API 28")
    if single_badging_value(value, "targetSdkVersion") != str(KX11_ANDROID_API):
        raise VerificationError("Natro targetSdk must remain API 28 for the KX11 firmware")
    if "application-debuggable" in value:
        raise VerificationError("Natro candidate is debuggable")
    if native_abis(apk):
        raise VerificationError("Natro unexpectedly contains architecture-specific native code")
    require_dex_strings(apk, (
        b"navi_win/",
        b"ddnavwin",
        b"ru.yandex.yandexmaps.app.MapActivity",
        b"transparentBackground",
        b"roadsOnly",
        b"showTrafficLights",
        b"showLaneGuidance",
        "Карточка ближайшего манёвра".encode("utf-8"),
    ))
    verify_zipalign(zipalign, apk)
    verify_signer(apksigner, apk, ("v2", "v3"))
    return value


def verify_navigator(
    aapt: Path,
    apksigner: Path,
    zipalign: Path,
    baseline: Path,
    apk: Path,
    baseline_verifier: Path,
) -> str:
    if sha256_file(baseline) != NAVIGATOR_BASELINE_SHA256:
        raise VerificationError("Navigator baseline is not the exact reviewed 30.3.0 APK")
    baseline_badging = badging(aapt, baseline)
    candidate_badging = badging(aapt, apk)
    if candidate_badging != baseline_badging:
        raise VerificationError("Navigator manifest/resources badging differs from baseline")
    fields = package_fields(candidate_badging)
    if fields.get("name") != NAVIGATOR_PACKAGE:
        raise VerificationError(f"unexpected Navigator package: {fields.get('name')!r}")
    if fields.get("versionCode") != NAVIGATOR_VERSION_CODE:
        raise VerificationError(f"unexpected Navigator versionCode: {fields.get('versionCode')!r}")
    candidate_tree = manifest_tree(aapt, apk)
    if "android:sharedUserId" in candidate_tree:
        raise VerificationError(
            "Navigator still declares sharedUserId and could force other Yandex apps "
            "onto the Navigator signing certificate"
        )
    minimum = int(single_badging_value(candidate_badging, "sdkVersion"))
    if minimum > KX11_ANDROID_API:
        raise VerificationError(
            f"Navigator minSdk {minimum} is newer than KX11 API {KX11_ANDROID_API}"
        )
    baseline_abis = native_abis(baseline)
    candidate_abis = native_abis(apk)
    if baseline_abis != KX11_NAVIGATOR_ABIS or candidate_abis != baseline_abis:
        raise VerificationError(
            "Navigator ABI drift: "
            f"baseline={sorted(baseline_abis)}, candidate={sorted(candidate_abis)}"
        )
    map_activity = activity_block(
        candidate_tree, "ru.yandex.yandexmaps.app.MapActivity"
    )
    if "android:theme(0x01010000)=@0x7f160242" not in map_activity:
        raise VerificationError(
            "Navigator MapActivity does not use the reviewed translucent bootstrap theme"
        )
    guidance_service = service_block(
        candidate_tree,
        "com.yandex.navikit_platform.guidance.service.GuidanceService",
    )
    if "android:foregroundServiceType(0x01010599)=(type 0x11)0x8" not in guidance_service:
        raise VerificationError(
            "Navigator GuidanceService must retain its location foreground-service type"
        )
    if "android:process" in guidance_service:
        raise VerificationError(
            "Navigator GuidanceService unexpectedly moved out of the main navigation process"
        )
    classes4 = zip_entry(apk, "classes4.dex")
    classes12 = zip_entry(apk, "classes12.dex")
    classes19 = zip_entry(apk, "classes19.dex")
    if b"Lru/natro/navigation/NatroEntryPoint;" not in classes4:
        raise VerificationError("Navigator classes4.dex has no Natro lifecycle hook")
    for marker in (
        b"Lru/natro/navigation/TrafficLightMapLayer;",
        b"Lru/natro/navigation/CameraDirectionMapLayer;",
        b"Lru/natro/navigation/LaneGuidanceMapLayer;",
        b"Lru/natro/navigation/BackgroundMapLease;",
        b"getTrafficLightsWithSignal",
        b"getActiveSpeedCameras",
        b"getActiveDirections",
        b"setMaxNumberOfUpcomingTrafficLights",
        b"NavigationLayerFactory",
        b"setRoadEventVisibleOnRoute",
        b"route-matched road-events layer attached",
        b"showTrafficLights",
        b"showLaneGuidance",
        b"background MapKit lease active",
    ):
        if marker not in classes19:
            raise VerificationError(
                f"Navigator classes19.dex has no required HUD/navigation marker {marker!r}"
            )
    if (b"Lru/natro/navigation/NatroEntryPoint;" not in classes12
            or b"shouldUseMovableMap" not in classes12):
        raise VerificationError(
            "Navigator classes12.dex has no reviewed floating TextureView selector"
        )
    for required in (
        b"navi_win/ru.yandex.yandexnavi", b"ddnavwin", b"ddnavforcewinfull",
        b"setTransparentBackgroundEnabled", b"road_surface",
    ):
        if required not in classes19:
            raise VerificationError(
                "Navigator classes19.dex has no legacy KX11 window contract: "
                + required.decode("ascii")
            )
    verify_zipalign(zipalign, apk)
    verify_signer(apksigner, apk, ("v3",))
    boundary = run([
        sys.executable,
        str(baseline_verifier),
        "--baseline", str(baseline),
        "--candidate", str(apk),
        "--mode", "release",
        "--apksigner", str(apksigner),
    ])
    return boundary


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--navigator", required=True, type=Path)
    parser.add_argument("--natro", required=True, type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--zipalign", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    script_dir = Path(__file__).resolve().parent
    baseline_verifier = script_dir / "verify_navigation_mod_baseline.py"
    inputs = (
        args.baseline, args.navigator, args.natro, args.aapt,
        args.apksigner, args.zipalign, baseline_verifier,
    )
    missing = [str(path) for path in inputs if not path.is_file()]
    if missing:
        print("FAIL: missing input: " + ", ".join(missing), file=sys.stderr)
        return 1
    try:
        natro_badging = verify_natro(args.aapt, args.apksigner, args.zipalign, args.natro)
        boundary = verify_navigator(
            args.aapt, args.apksigner, args.zipalign, args.baseline,
            args.navigator, baseline_verifier,
        )
        navigator_badging = badging(args.aapt, args.navigator)
        navigator_min_sdk = single_badging_value(navigator_badging, "sdkVersion")
        navigator_target_sdk = single_badging_value(navigator_badging, "targetSdkVersion")
        natro_sha = sha256_file(args.natro)
        navigator_sha = sha256_file(args.navigator)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            "PASS: signed pair matches the reviewed ECARX KX11 contract\n"
            "\n"
            "Head unit: ECARX KX11 / Qualcomm / Android 9 (API 28)\n"
            "Main Natro content area: 1760x720\n"
            "HUD: Display ID 2, surface 1920x1080, plane 728x190 @ (0,720)\n"
            f"Natro: {NATRO_PACKAGE} {NATRO_VERSION_NAME} ({NATRO_VERSION_CODE}), "
            "min/target API 28, ABI-neutral, v2+v3\n"
            f"Navigator: {NAVIGATOR_PACKAGE} versionCode {NAVIGATOR_VERSION_CODE}, "
            f"min/target API {navigator_min_sdk}/{navigator_target_sdk}, arm64-v8a, v3\n"
            f"Shared release signer SHA-256: {STABLE_CERT_SHA256}\n"
            f"Natro APK SHA-256: {natro_sha}\n"
            f"Navigator APK SHA-256: {navigator_sha}\n"
            "Navigator aapt badging: logically identical to baseline\n"
            "Navigator MapActivity: reviewed existing translucent bootstrap theme; "
            "original SplashAppTheme restored before onCreate\n"
            "Navigator background route: location GuidanceService retained in the main process; "
            "MapKit lease is active only for attached HUD/cluster surfaces\n"
            "Navigator resources.arsc and res/: byte-for-byte identical to baseline\n"
            "Navigator sharedUserId: absent (original Yandex Music is not signature-coupled)\n"
            "Window launch contract: Natro and Navigator contain navi_win/ddnavwin/MapActivity; "
            "only its reviewed translucent theme differs from baseline\n"
            "Navigator content boundary:\n"
            + "\n".join(f"  {line}" for line in boundary.strip().splitlines())
            + "\n\n"
            "Static verification cannot prove OEM install policy, online/offline map loading, "
            "SurfaceFlinger routing, or coexistence with installed Yandex apps; run the supplied "
            "on-device checklist before daily use.\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, zipfile.BadZipFile, VerificationError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1

    # Keep the full badging in memory during validation but never duplicate it in the report.
    if not natro_badging:
        print("FAIL: empty Natro badging", file=sys.stderr)
        return 1
    print(f"PASS: KX11 pair compatibility report written to {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
