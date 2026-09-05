"""Byte-provenance and recursive decoding regression fixtures, without vehicle data."""
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("known_baseline", ROOT / "build_known_baseline.py")
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def newc(entries):
    result = bytearray()
    for name, content, mode in entries + [("TRAILER!!!", b"", 0)]:
        name = name.encode() + b"\0"
        fields = [1, mode, 0, 0, 1, 0, len(content), 0, 0, 0, 0, len(name), 0]
        header = b"070701" + b"".join(f"{value:08x}".encode() for value in fields)
        result.extend(header + name)
        result.extend(b"\0" * ((-(110 + len(name))) % 4))
        result.extend(content)
        result.extend(b"\0" * ((-len(content)) % 4))
    return bytes(result)


class BaselineTest(unittest.TestCase):
    def test_nested_magic_zip_tar_gzip_cpio_hashes_real_leaf_bytes(self):
        dex = b"dex\n035\0" + b"synthetic fixture only" * 7
        config = b"channel=read-only\n"
        excluded = b"not a real acquired object: " + b"a" * 64
        inner = newc([("no_extension", dex, stat.S_IFREG | 0o644),
                      ("etc/vehicle.conf", config, stat.S_IFREG | 0o644),
                      ("etc/shadow", b"#!secret content", stat.S_IFREG | 0o600),
                      ("receipt.txt", excluded, stat.S_IFREG | 0o644)])
        compressed = gzip.compress(inner, mtime=0)
        tar_buffer = io.BytesIO()
        with tarfile.open(fileobj=tar_buffer, mode="w") as archive:
            info = tarfile.TarInfo("payload_without_extension")
            info.size = len(compressed)
            archive.addfile(info, io.BytesIO(compressed))
            link = tarfile.TarInfo("link.dex")
            link.type = tarfile.SYMTYPE
            link.linkname = "/etc/shadow"
            archive.addfile(link)
        zipped = io.BytesIO()
        with zipfile.ZipFile(zipped, "w") as archive:
            archive.writestr("another_extensionless_payload", tar_buffer.getvalue())
        payload = zipped.getvalue()
        scanner = baseline.Scanner()
        scanner.visit(io.BytesIO(payload), len(payload), ["opaque_original"])
        self.assertEqual(scanner.errors, [])
        self.assertTrue({digest(payload), digest(tar_buffer.getvalue()), digest(compressed),
                         digest(inner), digest(dex), digest(config)} <= scanner.hashes)
        self.assertNotIn(digest(excluded), scanner.hashes)
        self.assertNotIn(digest(b"#!secret content"), scanner.hashes)
        self.assertNotIn("a" * 64, scanner.hashes)
        self.assertEqual(scanner.hashes, set(scanner.records))
        self.assertEqual(scanner.counts["expanded_cpio_newc"], 1)

    def test_magic_never_overrides_secret_file_exclusions(self):
        for name in ("etc/shadow", "etc/credentials.json", "keys/archive.zip", "boot/server.key",
                     "etc/ssh_host_rsa_key", "scripts/token.py", "etc/private.pfx"):
            with self.subTest(name=name):
                self.assertIsNone(baseline.selected_kind(name, b"#!/bin/sh\n"))
        self.assertIsNone(baseline.selected_kind("etc/arbitrary.conf", b"-----BEGIN OPENSSH PRIVATE KEY-----\n"))

    def test_expected_inventory_and_extra_sidecar_do_not_import_referenced_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            code = b"dex\n035\0synthetic code"
            (root / "program").write_bytes(code)
            missing = "e" * 64
            (root / "program.sha256").write_text(missing + " program\n")
            registry = root / "registry.json"
            registry.write_text(json.dumps({"sources": [{"name": "program", "sha256": digest(code)}]}))
            sdk = root / "sdk.json"
            sdk.write_text(json.dumps({"sources": [{"sha256": missing}]}))
            output, validation = root / "known.gz", root / "validation.json"
            baseline.build(root, registry, sdk, output, validation, ["program.sha256"])
            actual = json.loads(gzip.decompress(output.read_bytes()))
            report = json.loads(validation.read_text())
            self.assertEqual(actual["sha256"], [digest(code)])
            self.assertEqual(report["available_inputs"], 1)
            self.assertEqual(report["available_total_inputs"], 2)
            self.assertEqual(report["sdk_not_observed"], [missing])
            self.assertFalse(report["extra_inputs"][0]["selected_in_baseline"])
            self.assertEqual(report["metadata_reference_hashes_imported"], 0)
            first = output.read_bytes()
            baseline.build(root, registry, sdk, output, validation, ["program.sha256"])
            self.assertEqual(first, output.read_bytes())

    def test_mismatched_registered_original_is_not_trusted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "program.dex").write_bytes(b"dex\n035\0changed")
            registry, sdk = root / "registry.json", root / "sdk.json"
            registry.write_text(json.dumps({"sources": [{"name": "program.dex", "sha256": "e" * 64}]}))
            sdk.write_text(json.dumps({"sources": []}))
            output, validation = root / "known.gz", root / "validation.json"
            self.assertEqual(baseline.build(root, registry, sdk, output, validation), 1)
            self.assertEqual(json.loads(gzip.decompress(output.read_bytes()))["sha256"], [])
            self.assertEqual(len(json.loads(validation.read_text())["registry_hash_mismatches"]), 1)


if __name__ == "__main__":
    unittest.main()
