#!/usr/bin/env python3
"""Build a deterministic hash-only baseline from real local corpus bytes.

No metadata hash is imported into the baseline. The registry chooses exact input
filenames; the SDK inventory is an expected-set verification only. ZIP, TAR,
GZIP, XZ, BZIP2 and newc CPIO are detected by magic, including hidden nested
firmware archives. No archive path is extracted to disk or executed. Temporary
spooled files hold only bytes being parsed and are deleted when closed.
"""

from __future__ import annotations

import argparse
import bz2
import collections
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import stat
import tarfile
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

CHUNK = 1024 * 1024
MAX_MEMBER = 1024 * 1024 * 1024
MAX_DEPTH = 12
MAX_ENTRIES = 500_000
LV_SHA = "4a0cd3a681364059ace2c857d4d7436e0902660c43601b48d0ef735e86d23680"
TS_DM_SHA = "b42926770416f0e05778a04aaa69b0b69c5dd9f1572babe88813a253df7aa8c1"
LV_GZIP_OFFSET = 19406128
CODE_SUFFIXES = (".apk", ".jar", ".aar", ".dex", ".odex", ".vdex", ".oat", ".art", ".class",
                 ".elf", ".bin", ".img", ".mbn", ".dtb", ".dtbo", ".ko", ".so", ".a",
                 ".sh", ".py", ".lua", ".rc", ".cmd", ".command", ".bat", ".js")
CONFIG_SUFFIXES = (".conf", ".cfg", ".ini", ".properties", ".xml", ".yaml", ".yml", ".json")
RUNTIME_PARTS = {"bin", "sbin", "lib", "lib64", "etc", "firmware", "boot", "scripts", "config", "configs"}
SECRET_NAMES = {"shadow", "passwd", "master.passwd", "pwd.db", "spwd.db",
                "credentials", "secrets", "tokens", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"}
SECRET_PARTS = {".ssh", ".gnupg", "keystore", "keys"}
LIMITATIONS = [
    "Hashes identify acquired bytes; matching disk files do not prove current runtime class origin, ECU identity, or physical behavior.",
    "Only exact locally available registered originals and explicitly supplied extras are scanned; absent or hash-only references are never added. Extras are accounted separately from the registry.",
    "Code/native/image/config candidates are selected by magic, suffix and reviewed runtime path components. Personal logs, PCAP, media and ordinary text are excluded.",
    "Duplicate containers are expanded once per content hash. No contents are executed or extracted by archive member path.",
    "ZIP/TAR/GZIP/XZ/BZIP2/newc CPIO are traversed. Generic disk filesystems, encrypted/proprietary containers and arbitrary embedded archive offsets are not decoded.",
    "One evidence-pinned Linux LV image has its known embedded gzip/newc initramfs decoded at byte 19406128; that exception requires the exact full image SHA-256.",
    "Resource limits are 1 GiB per member, 12 nesting levels and 500000 member headers. Any hit or parsing failure is reported, never treated as complete.",
]


def sha_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def magic_kind(head):
    if head.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")):
        return "zip"
    if head.startswith(b"\x1f\x8b"):
        return "gzip"
    if head.startswith(b"\xfd7zXZ\x00"):
        return "xz"
    if head.startswith(b"BZh"):
        return "bzip2"
    if head.startswith((b"070701", b"070702")):
        return "cpio_newc"
    if len(head) >= 512 and head[257:262] == b"ustar":
        return "tar"
    if head.startswith(b"\x7fELF"):
        return "elf"
    if head.startswith((b"dex\n", b"cdex")):
        return "dex"
    if head.startswith(b"\xca\xfe\xba\xbe"):
        return "jvm_class"
    if head.startswith(b"#!"):
        return "script"
    return None


def selected_kind(name, head):
    name = name.lower()
    basename = PurePosixPath(name).name
    parts = set(PurePosixPath(name).parts)
    if (basename in SECRET_NAMES or parts & SECRET_PARTS
            or basename.startswith("ssh_host_")
            or basename.endswith((".key", ".pem", ".p12", ".pfx", ".jks", ".keystore"))
            or re.search(r"(?:^|[._-])(?:passwords?|credentials?|secrets?|tokens?)(?:[._-]|$)", basename)
            or re.search(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY(?: BLOCK)?-----", head)):
        return None
    # Secret exclusion precedes magic: a renamed key or secret-bearing script
    # must not become a direct baseline entry just because it starts with #!.
    kind = magic_kind(head)
    if kind:
        return kind
    if name.endswith(CODE_SUFFIXES) or ".so." in basename:
        return "code_or_image"
    if name.endswith((".conf", ".cfg", ".ini", ".properties")):
        return "config"
    # JSON/XML diagnostics and receipts are not firmware configs merely because
    # their extension is structured. A runtime location is required for these.
    if parts & RUNTIME_PARTS:
        if name.endswith(CONFIG_SUFFIXES) or ("etc" in parts and name.endswith(".txt")):
            return "config"
        if "." not in basename and len(head) and b"\x00" in head:
            return "runtime_binary"
    return None


class SliceReader:
    def __init__(self, source, remaining):
        self.source, self.remaining = source, remaining

    def read(self, size=-1):
        size = self.remaining if size < 0 else min(size, self.remaining)
        data = self.source.read(size)
        self.remaining -= len(data)
        return data


class Scanner:
    def __init__(self):
        self.hashes = set()
        self.expanded = set()
        self.records = {}
        self.inputs = []
        self.errors = []
        self.counts = collections.Counter()
        self.witnesses = {}

    def error(self, chain, message):
        self.errors.append({"path_chain": chain, "error": str(message)[:1000]})

    def visit(self, source, size, chain, depth=0, outer=False):
        self.counts["member_headers"] += 1
        if self.counts["member_headers"] > MAX_ENTRIES:
            raise ValueError("member header limit exceeded")
        if depth > MAX_DEPTH:
            self.error(chain, "archive nesting limit exceeded")
            return
        name = chain[-1]
        head = source.read(1024)
        kind = selected_kind(name, head)
        if kind is None:
            self.counts["excluded_non_code_config"] += 1
            return
        if size is not None and size > MAX_MEMBER:
            self.error(chain, "selected member exceeds 1 GiB")
            return
        digest = hashlib.sha256()
        total = 0
        with tempfile.SpooledTemporaryFile(max_size=16 * CHUNK) as spool:
            data = head
            while data:
                total += len(data)
                if total > MAX_MEMBER:
                    self.error(chain, "expanded member exceeds 1 GiB")
                    return
                digest.update(data)
                spool.write(data)
                data = source.read(CHUNK)
            if size is not None and total != size:
                self.error(chain, "member byte count mismatch")
                return
            sha = digest.hexdigest()
            self.hashes.add(sha)
            self.counts["selected_occurrences"] += 1
            self.counts["selected_bytes_hashed"] += total
            if sha not in self.records:
                self.records[sha] = {"sha256": sha, "bytes": total, "kind": kind, "path_chain": chain}
            if "KX11-Cruise-Trace-20260809-001402.zip" == chain[0] and name.endswith("/com_ts_dm_service/ts-dm-service.apk"):
                self.witnesses["ts_dm_service_from_cruise_001402"] = dict(self.records[sha], path_chain=chain)
            if sha == LV_SHA:
                self.witnesses["qnx_linux_lv_image"] = self.records[sha]
            if name.endswith("/qnx/files/usr/bin/cluster_controller"):
                self.witnesses["qnx_cluster_controller"] = self.records[sha]
            if sha in self.expanded:
                self.counts["duplicate_container_or_leaf"] += 1
                return
            self.expanded.add(sha)
            spool.seek(0)
            try:
                if kind == "zip":
                    self.counts["expanded_zip"] += 1
                    with zipfile.ZipFile(spool) as archive:
                        for info in sorted(archive.infolist(), key=lambda item: item.filename):
                            if info.is_dir() or stat.S_ISLNK(info.external_attr >> 16):
                                continue
                            with archive.open(info) as member:
                                self.visit(member, info.file_size, chain + [info.filename], depth + 1)
                elif kind == "tar":
                    self.counts["expanded_tar"] += 1
                    with tarfile.open(fileobj=spool, mode="r:") as archive:
                        for info in archive:
                            if info.isfile():
                                with archive.extractfile(info) as member:
                                    self.visit(member, info.size, chain + [info.name], depth + 1)
                elif kind in ("gzip", "xz", "bzip2"):
                    self.counts["expanded_" + kind] += 1
                    factory = {"gzip": gzip.GzipFile, "xz": lzma.LZMAFile, "bzip2": bz2.BZ2File}[kind]
                    inner_name = name.rsplit(".", 1)[0] if "." in name else name + "[decompressed]"
                    decoder = factory(fileobj=spool, mode="rb") if kind == "gzip" else factory(spool, "rb")
                    with decoder as member:
                        self.visit(member, None, chain + [inner_name], depth + 1)
                elif kind == "cpio_newc":
                    self.counts["expanded_cpio_newc"] += 1
                    self.cpio(spool, chain, depth + 1)
                elif sha == LV_SHA:
                    spool.seek(LV_GZIP_OFFSET)
                    if spool.read(3) != b"\x1f\x8b\x08":
                        raise ValueError("pinned LV image lacks expected embedded gzip magic")
                    spool.seek(LV_GZIP_OFFSET)
                    # gzip.GzipFile expects all trailing bytes to be gzip; the
                    # pinned kernel image also contains its non-gzip suffix.
                    import zlib
                    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
                    with tempfile.SpooledTemporaryFile(max_size=16 * CHUNK) as decoded:
                        decoded_bytes = 0
                        while not decoder.eof:
                            chunk = spool.read(CHUNK)
                            if not chunk:
                                raise ValueError("pinned LV gzip stream ended early")
                            chunk = decoder.decompress(chunk)
                            decoded_bytes += len(chunk)
                            if decoded_bytes > MAX_MEMBER:
                                raise ValueError("LV initramfs exceeds member limit")
                            decoded.write(chunk)
                        decoded.seek(0)
                        self.visit(decoded, decoded_bytes, chain + ["[embedded-gzip@19406128]/initramfs.cpio"], depth + 1)
            except Exception as exc:
                self.error(chain, exc)

    def cpio(self, source, chain, depth):
        while True:
            header = source.read(110)
            if len(header) != 110 or header[:6] not in (b"070701", b"070702"):
                raise ValueError("invalid/truncated newc header")
            fields = [int(header[6 + i * 8:14 + i * 8], 16) for i in range(13)]
            mode, size, name_size = fields[1], fields[6], fields[11]
            if not 1 <= name_size <= 4096:
                raise ValueError("newc filename bound exceeded")
            name_raw = source.read(name_size)
            if len(name_raw) != name_size or name_raw[-1:] != b"\x00":
                raise ValueError("invalid/truncated newc filename")
            name = name_raw[:-1].decode("utf-8", "surrogateescape")
            source.seek((-(110 + name_size)) % 4, os.SEEK_CUR)
            if name == "TRAILER!!!":
                return
            begin = source.tell()
            if stat.S_ISREG(mode):
                self.visit(SliceReader(source, size), size, chain + [name], depth)
            source.seek(begin + size + (-size) % 4)


def build(originals, registry, sdk_inventory, output, validation, extras=(), private_records=None):
    registered = json.loads(Path(registry).read_text())
    names = sorted({entry["name"] for entry in registered["sources"]})
    expected_inputs = {entry["name"]: entry for entry in registered["sources"]}
    extras = sorted(set(extras) - set(names))
    scanner = Scanner()
    absent = []
    extra_inputs = []
    mismatches = []
    for index, name in enumerate(names + extras, 1):
        if (Path(name).name != name or name.startswith(".")
                or ".openai-download" in name):
            raise ValueError("input must be one complete non-partial original filename")
        path = Path(originals) / name
        if not path.is_file() or path.is_symlink():
            absent.append(name)
            continue
        sha = sha_file(path)
        item = {"name": name, "bytes": path.stat().st_size, "sha256": sha}
        if name in expected_inputs:
            expected = expected_inputs[name]
            if expected.get("sha256") and expected["sha256"] != sha:
                mismatches.append({"name": name, "expected_sha256": expected["sha256"],
                                   "actual_sha256": sha})
                continue
            scanner.inputs.append(item)
        else:
            extra_inputs.append(item)
        print("%d/%d %s" % (index, len(names) + len(extras), name), flush=True)
        try:
            with path.open("rb") as source:
                scanner.visit(source, path.stat().st_size, [name], outer=True)
        except Exception as exc:
            scanner.error([name], exc)
    sdk = json.loads(Path(sdk_inventory).read_text())
    sdk_expected = {entry["sha256"] for entry in sdk["sources"]}
    missing_sdk = sorted(sdk_expected - scanner.hashes)
    for item in extra_inputs:
        item["selected_in_baseline"] = item["sha256"] in scanner.hashes
        item["reference_hashes_imported"] = False
    required = {"ts_dm_service": TS_DM_SHA, "qnx_linux_lv_image": LV_SHA}
    missing_required = {key: sha for key, sha in required.items() if sha not in scanner.hashes}
    baseline = {"schema": "geely-known-code-files-v1", "sha256": sorted(scanner.hashes),
                "source_registry_sha256": sha_file(registry),
                "input_set_sha256": hashlib.sha256(json_bytes(scanner.inputs + extra_inputs)).hexdigest(),
                "selection": "code-native-image-config-and-archive-bytes", "contains_raw_content": False}
    payload = json_bytes(baseline)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0, compresslevel=9) as packed:
            packed.write(payload)
    kinds = collections.Counter(record["kind"] for record in scanner.records.values())
    result = {"schema": "geely-known-baseline-validation-v1", "registry_entries": len(names),
              "available_inputs": len(scanner.inputs), "unique_input_hashes": len({x["sha256"] for x in scanner.inputs}),
              "absent_inputs": absent, "unique_baseline_hashes": len(scanner.hashes),
              "extra_inputs": extra_inputs, "available_total_inputs": len(scanner.inputs) + len(extra_inputs),
              "registry_hash_mismatches": mismatches,
              "unique_kinds": dict(sorted(kinds.items())), "counts": dict(sorted(scanner.counts.items())),
              "sdk_expected": len(sdk_expected), "sdk_verified_from_bytes": len(sdk_expected & scanner.hashes),
              "sdk_not_observed": missing_sdk, "required_witnesses": scanner.witnesses,
              "sdk_inventory_sha256": sha_file(sdk_inventory),
              "sdk_inventory_used_only_as_expected_set": True,
              "required_hashes": required, "required_hashes_not_observed": missing_required,
              "all_baseline_hashes_have_byte_witness": scanner.hashes == set(scanner.records),
              "metadata_reference_hashes_imported": 0,
              "errors": scanner.errors, "inputs": scanner.inputs, "limitations": LIMITATIONS,
              "baseline_gzip_sha256": sha_file(output), "baseline_gzip_bytes": output.stat().st_size,
              "baseline_json_sha256": hashlib.sha256(payload).hexdigest(),
              "baseline_sorted_unique": baseline["sha256"] == sorted(set(baseline["sha256"])),
              "deterministic_gzip_mtime": 0}
    result["complete_within_declared_scope"] = not (
        scanner.errors or missing_sdk or absent or mismatches or missing_required)
    if private_records:
        private_records = Path(private_records)
        private_records.parent.mkdir(parents=True, exist_ok=True)
        private_records.write_bytes(json_bytes({"records": [scanner.records[sha] for sha in sorted(scanner.records)]}))
        result["private_byte_witness_records_sha256"] = sha_file(private_records)
    Path(validation).write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("available_inputs", "unique_baseline_hashes", "sdk_expected", "sdk_verified_from_bytes", "sdk_not_observed", "errors", "baseline_gzip_bytes")}), flush=True)
    return 0 if result["complete_within_declared_scope"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--originals", required=True, type=Path)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--sdk-inventory", required=True, type=Path)
    parser.add_argument("--extra", action="append", default=[], help="Exact extra original filename; accounted outside the registry")
    parser.add_argument("--private-records", type=Path, help="Internal byte-witness inventory; never publish this file")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("known_files.json.gz"))
    parser.add_argument("--validation", type=Path, default=Path(__file__).with_name("VALIDATION_BASELINE.json"))
    args = parser.parse_args()
    raise SystemExit(build(args.originals, args.registry, args.sdk_inventory, args.output,
                          args.validation, args.extra, args.private_records))


if __name__ == "__main__":
    main()
