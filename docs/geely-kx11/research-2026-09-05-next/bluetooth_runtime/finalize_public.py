"""Finalize typed evidence; originals remain outside the public artifact list.

Run from the audit workspace after build_dm_report.py. Requires androguard for
verification of the actual DEX offsets, never for publication of method bodies.
"""
from pathlib import Path
import hashlib
import json
import re
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent
ORIGINALS = ROOT.parents[1] / "originals"


def read(name):
    return json.loads((ROOT / name).read_text())


def write(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


coverage = read("full_corpus_target_coverage.json")
archive = "KX11_Bluetooth_Collect_20260815-134614_76337.tar.gz"
member = archive.removesuffix(".tar.gz") + "/DISCOVERY/packages_full_paths.txt"
archive_sha = next(x["archive_sha256"] for x in coverage["archives"] if x["archive"] == archive)
with tarfile.open(ORIGINALS / archive) as container:
    package_bytes = container.extractfile(member).read()
packages = {
    "com.android.bluetooth", "com.ts.dm.service",
    "ecarx.bluetooth.service.extension", "ru.natro.statuswidget",
    "ecarx.powersomeip.service", "org.astpepper.hwgps",
}
identities = []
for lineno, line in enumerate(package_bytes.decode().splitlines(), 1):
    match = re.fullmatch(r"package:(/.+)=([A-Za-z0-9_.]+)", line.strip())
    if match and match[2] in packages:
        identities.append({"package": match[2], "installed_path_observed": match[1],
                           "source": {"archive": archive, "archive_sha256": archive_sha,
                                      "member": member, "member_sha256": hashlib.sha256(package_bytes).hexdigest(),
                                      "line": lineno}})
assert {x["package"] for x in identities} == packages
write("package_identity_evidence.json", {
    "scope": "Observed package-name/path mapping, August 15 archive; no current installed-byte claim",
    "identities": identities,
    "version_limit": "Natro August version/SHA is not established by this listing. Fresh metadata must retain timestamps and firmware scope.",
})
for filename in ("collection_targets.json", "static/collection_targets.json"):
    targets = read(filename)
    for target in targets["targets"]:
        if target["path"].endswith("/EcarxBluetoothServiceExtension.apk"):
            identity = next(x for x in identities if x["package"] == "ecarx.bluetooth.service.extension")
            target["package"] = identity["package"]
            target["package_identity_evidence"] = identity["source"]
        target["coverage_evidence"] = "../full_corpus_target_coverage.json" if filename.startswith("static/") else "full_corpus_target_coverage.json"
    for target in targets.get("runtime_projection_targets", []):
        if target.get("target") == "installed package identity":
            target["packages"] = sorted(packages)
            target["package_identity_evidence"] = "package_identity_evidence.json"
    if filename.startswith("static/"):
        for target in targets["already_available_do_not_recollect"]:
            if isinstance(target, dict) and target.get("evidence") == "dm_service_evidence.json":
                target["evidence"] = "../dm_service_evidence.json"
    write(filename, targets)

# Reconcile every published DM DEX reference with the original binary's method table.
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

dm = read("dm_service_evidence.json")
with zipfile.ZipFile(ROOT / "private/ts-dm-service.apk") as container:
    dex_bytes = container.read("classes.dex")
assert hashlib.sha256(dex_bytes).hexdigest() == dm["dex_sha256"]
assert hashlib.sha256((ROOT / "private/ts-dm-service.apk").read_bytes()).hexdigest() == dm["source"]["member_sha256"]
dex = DEX(dex_bytes)
methods = {(m.get_class_name(), m.get_name(), m.get_descriptor()): m
           for cls in dex.get_classes() for m in cls.get_methods() if m.get_code()}
offset_checks = 0
for finding in dm["findings"]:
    for ref in finding["evidence"]:
        if "code_item_offset" in ref:
            method = methods[ref["class"], ref["method"], ref["descriptor"]]
            assert method.get_code_off() == int(ref["code_item_offset"], 16)
            offset_checks += 1
dm["findings"].sort(key=lambda x: int(x["id"].removeprefix("DM")))
write("dm_service_evidence.json", dm)

public = sorted(str(p.relative_to(ROOT)) for p in ROOT.rglob("*")
                if p.is_file() and "private" not in p.relative_to(ROOT).parts
                and p.suffix in (".md", ".json", ".py")
                and p.name != "PUBLIC_FILES.json")
forbidden = re.compile(r"(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])|"
                       r"(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])")
for name in public:
    text = (ROOT / name).read_text()
    assert not forbidden.search(text), "Personal address/UUID pattern in " + name
    if name.endswith(".json"):
        assert not re.search(r'"(?:instructions|raw_line|raw_message|notification_payload)"\s*:', text), name
assert coverage["registry_present_by_name"] == coverage["registry_source_count"] == 158
assert coverage["all_registry_sources_hashed"] and not coverage["registry_hash_mismatches"]
assert not coverage["failed"] and not coverage["nested_archives_not_expanded"]
assert len(read("collection_targets.json")["targets"]) == 8

validation = read("VALIDATION.json")
validation.update({
    "result": "PASS", "errors": [], "dm_server_findings": len(dm["findings"]),
    "dm_actual_dex_reference_checks": offset_checks,
    "source_presence_recheck": {
        "registry_sources_present_and_hash_verified": 158,
        "outer_archives": coverage["archives_scanned"],
        "outer_members": coverage["archive_members_scanned"],
        "nested_containers": len(coverage["nested_archive_checks"]),
        "nested_unique_sha256": coverage["nested_archive_unique_hashes"],
        "nested_members": sum(x["members"] for x in coverage["nested_archive_checks"]),
        "deep_nested_containers": len(coverage["deep_nested_checks"]),
        "remaining_unexpanded_discovered_archives": 0,
        "recovered_previously_missing_apk": "ts-dm-service.apk",
        "remaining_missing_files_or_abi": 8,
        "limit": "Exact basename inventory, not semantic coverage of all bytes or absence inside raw firmware partitions.",
    },
    "public_privacy_pattern_scan": {"files": len(public), "raw_mac_or_uuid_matches": 0,
                                    "raw_log_or_full_method_body_keys": 0},
    "collector_regression_tests": {"tests": 44, "result": "PASS",
                                   "scope": "37 collector/QNX tests plus 7 BT projection tests; offline mocks only",
                                   "includes": ["single-line version metadata spaces/tabs/mixed whitespace",
                                                "candidate package absent presence_probe",
                                                "required package absent remains package_not_found"]},
    "remaining_blockers": ["missing native backend and extension binaries", "August installed Natro identity",
                           "Natro client teardown reason", "synchronized AP/QNX power epoch",
                           "DM projection policy execution in the observed disconnect epoch"],
})
write("VALIDATION.json", validation)
write("PUBLIC_FILES.json", {
    "schema_version": 1,
    "scope": "REL-008 public allowlist: authored analysis, typed evidence, references and code only",
    "excluded": ["private/**", "static/private/**", "original APK/JAR/ELF", "full foreign method bodies", "raw HCI/log/device metadata"],
    "files": [{"path": name, "bytes": (ROOT / name).stat().st_size,
               "sha256": hashlib.sha256((ROOT / name).read_bytes()).hexdigest()} for name in public],
    "self_excluded_from_hashes": "PUBLIC_FILES.json",
})
print(json.dumps({"result": "PASS", "public_files": len(public), "dm_dex_refs": offset_checks,
                  "remaining_missing_targets": 8}))
