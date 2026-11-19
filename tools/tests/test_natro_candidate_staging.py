"""Packaging checks use a synthetic APK; they do not replace real CI or signing."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools import sign_natro_candidate, stage_natro_adb_candidate


class CandidateStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="natro-stage-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.previous_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.previous_cwd)
        self.environment = patch.dict(os.environ, {
            "VERSION_NAME": "3.0.0", "VERSION_CODE": "208021333",
            "RUNNER_TEMP": str(self.root), "ANDROID_HOME": str(self.root / "sdk"),
            "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123",
            "REUSE_NAVIGATOR_300": "false", "REUSE_NAVIGATOR_302": "false",
            "REUSE_NAVIGATOR_339": "false", "REUSE_NAVIGATOR_349": "false",
            "ALICE_ENGINE_HOOKS": "false", "NAVIGATOR_TURN_BASE_APK": "",
            "HELPER_CHANGED": "false", "NATRO_STANDALONE": "false",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.apk = self.root / "app/build/outputs/apk/geely/release/app-unsigned.apk"
        self.apk.parent.mkdir(parents=True)
        self.index = self.root / "app/src/main/resources/qnx-collected-20261017.tsv"
        self.index.parent.mkdir(parents=True)
        self.index.write_bytes(b"# inert exclusion fixture\n")
        self.write_apk()
        self.report = self.root / "app/build/test-results/testGeelyDebugUnitTest/TEST-example.xml"
        self.report.parent.mkdir(parents=True)
        self.report.write_text('<testsuite tests="1906" failures="0" errors="0" skipped="0"/>')
        sdk = self.root / "sdk/build-tools/36.0.0"
        for name in ("aapt", "apksigner", "zipalign", "dexdump", "lib/apksigner.jar", "lib64/libc++.so"):
            target = sdk / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(name.encode())
        self.dex = self.root / "build/navigation-mod/classes19.dex"
        self.dex.parent.mkdir(parents=True)
        self.dex.write_bytes(b"dex-fixture" * 1000)
        self.out = self.root / "natro-3.0.0-candidate"

    def write_apk(self, missing=None):
        with zipfile.ZipFile(self.apk, "w") as apk:
            apk.writestr(self.index.name, self.index.read_bytes())
            for asset in ("lan/index.html", "lan/client.js", "lan/client.css", "natro-wechat-compat.apk"):
                if asset != missing:
                    apk.writestr("assets/" + asset, "test fixture")

    def stage(self):
        with patch.object(stage_natro_adb_candidate.subprocess, "check_output", return_value="b" * 40 + "\n"), contextlib.redirect_stdout(io.StringIO()):
            stage_natro_adb_candidate.main()

    def test_complete_provenance_and_exact_copies(self):
        self.stage()
        manifest = json.loads((self.out / "candidate.json").read_text())
        self.assertEqual("a" * 40, manifest["sourceCommit"])
        self.assertEqual("b" * 40, manifest["sourceTree"])
        self.assertEqual(1906, manifest["unitTests"]["tests"])
        self.assertEqual(0, manifest["unitTests"]["failures"])
        self.assertEqual("pending", manifest["physicalKx11Verification"])
        self.assertTrue(manifest["navigator"]["pairRequired"])
        self.assertFalse(manifest["helperChanged"])
        self.assertEqual(["classes19.dex"], manifest["navigator"]["allowedPayloadChanges"])
        self.assertEqual(self.dex.read_bytes(), (self.out / "classes19.dex").read_bytes())
        self.assertEqual(hashlib.sha256(self.dex.read_bytes()).hexdigest(), manifest["navigator"]["patchSha256"])
        self.assertEqual(self.apk.read_bytes(), (self.out / "Natro-3.0.0-unsigned.apk").read_bytes())
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), manifest["unsignedSha256"])
        self.assertEqual(b"lib/apksigner.jar", (self.out / "tools/lib/apksigner.jar").read_bytes())
        self.assertTrue((self.out / "test-results/TEST-example.xml").is_file())

    def test_351_rejects_wrong_or_absent_turn_baseline(self):
        with patch.dict(os.environ, {"VERSION_NAME": "3.5.1", "VERSION_CODE": "208021384"}):
            with self.assertRaisesRegex(ValueError, "new pair"):
                self.stage()
        out = self.root / "natro-3.5.1-candidate"
        self.assertFalse((out / "candidate.json").exists())

    def test_351_requires_the_reviewed_349_bytes(self):
        env = {"VERSION_NAME": "3.5.1", "VERSION_CODE": "208021384", "NAVIGATOR_TURN_BASE_APK": str(self.apk)}
        with patch.dict(os.environ, env), self.assertRaisesRegex(ValueError, "exact signed 3.4.9"):
            self.stage()
        self.assertFalse((self.root / "natro-3.5.1-candidate/candidate.json").exists())

    def test_turn_payload_rejects_any_other_changed_added_or_removed_entry(self):
        from tools.navigation_turn_candidate import CHANGES, changed_hashes
        before = {name: "old" for name in (*CHANGES, "resources.arsc")}
        after = dict(before, **{name: "new" for name in CHANGES})
        self.assertEqual({name: "new" for name in CHANGES}, changed_hashes(before, after))
        for rejected in (dict(after, **{"resources.arsc": "changed"}), dict(after, added="extra"),
                         {name: value for name, value in after.items() if name != "resources.arsc"},
                         dict(after, **{"classes3.dex": "old"})):
            with self.subTest(rejected=rejected), self.assertRaises(ValueError):
                changed_hashes(before, rejected)

    def test_failed_tests_stop_publication(self):
        self.report.write_text('<testsuite tests="1906" failures="1" errors="0"/>')
        with self.assertRaisesRegex(ValueError, "Full test suite must pass"):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_wrong_packaged_exclusions_stop_publication(self):
        self.index.write_bytes(b"# changed source\n")
        with self.assertRaisesRegex(ValueError, "exclusion index differs"):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_missing_navigator_patch_stops_pair_staging(self):
        self.dex.unlink()
        with self.assertRaisesRegex(ValueError, "Compiled Navigator patch"):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_reuse_refuses_a_different_navigator_patch(self):
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_300": "true"}):
            with self.assertRaisesRegex(ValueError, "exact verified 3.0.0 patch"):
                self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_missing_asset_stops_publication(self):
        self.write_apk(missing="natro-wechat-compat.apk")
        with self.assertRaises(KeyError):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_302_reuse_refuses_a_different_navigator_patch(self):
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_302": "true"}):
            with self.assertRaisesRegex(ValueError, "exact verified 3.0.2 patch"):
                self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_339_reuse_refuses_a_different_navigator_patch(self):
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_339": "true"}):
            with self.assertRaisesRegex(ValueError, "exact verified 3.3.9 patch"):
                self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_reuses_published_349_only_when_compiled_bridge_matches(self):
        published = self.root / "docs/releases/3.4.9/navigator-release-report.json"
        published.parent.mkdir(parents=True)
        digest = hashlib.sha256(self.dex.read_bytes()).hexdigest()
        published.write_text(json.dumps({
            "ciBridgeSha256": digest, "versionName": "30.3.0 Natro 3.4.9", "sha256": "a" * 64,
        }))
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_349": "true"}):
            self.stage()
        nav = json.loads((self.out / "candidate.json").read_text())["navigator"]
        self.assertFalse(nav["pairRequired"])
        self.assertEqual("a" * 64, nav["baselineSha256"])
        self.assertEqual([], nav["allowedPayloadChanges"])

    def test_changed_349_bridge_requires_separate_navigator_release(self):
        published = self.root / "docs/releases/3.4.9/navigator-release-report.json"
        published.parent.mkdir(parents=True)
        published.write_text(json.dumps({"ciBridgeSha256": "0" * 64}))
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_349": "true"}):
            with self.assertRaisesRegex(ValueError, "bridge differs"):
                self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_conflicting_reuse_baselines_stop_publication(self):
        with patch.dict(os.environ, {"REUSE_NAVIGATOR_300": "true", "REUSE_NAVIGATOR_302": "true"}):
            with self.assertRaisesRegex(ValueError, "exactly one verified Navigator"):
                self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_helper_update_is_explicit_in_candidate(self):
        with patch.dict(os.environ, {"HELPER_CHANGED": "true"}):
            self.stage()
        manifest = json.loads((self.out / "candidate.json").read_text())
        self.assertTrue(manifest["helperChanged"])

    def test_native_engine_pair_records_exact_tool_and_payload_scope(self):
        native=self.root/'build/alice-focus-tool';native.mkdir()
        (native/'PatchAliceFocus.class').write_bytes(b'compiled fixture')
        with patch.dict(os.environ, {"ALICE_ENGINE_HOOKS": "true"}): self.stage()
        nav=json.loads((self.out/'candidate.json').read_text())['navigator']
        self.assertTrue(nav['nativeEngineHooks'])
        self.assertEqual(['classes11.dex','classes15.dex','classes19.dex'],nav['allowedPayloadChanges'])
        self.assertEqual('52736b41714bba696cd54442804816741e2834b57a0d880702560667106a89dc',nav['baselineSha256'])
        self.assertEqual(hashlib.sha256(b'compiled fixture').hexdigest(),nav['nativeToolSha256']['PatchAliceFocus.class'])
        self.assertEqual(b'compiled fixture',(self.out/'alice-focus-tool/PatchAliceFocus.class').read_bytes())

    def test_native_engine_without_compiled_hook_tool_is_rejected(self):
        with patch.dict(os.environ, {"ALICE_ENGINE_HOOKS": "true"}):
            with self.assertRaisesRegex(ValueError,'Native hook tool not built'): self.stage()
        self.assertFalse((self.out/'candidate.json').exists())

    def test_streaming_hashes_support_python_310(self):
        payload = self.root / "hash-fixture"
        content = b"streamed" * 300000
        payload.write_bytes(content)
        expected = hashlib.sha256(content).hexdigest()
        self.assertEqual(expected, stage_natro_adb_candidate.sha256(payload))
        self.assertEqual(expected, sign_natro_candidate.sha256(payload))

    def standalone_environment(self):
        def git(*args):
            return subprocess.check_output(["git", *args], text=True).strip()
        git("init", "--quiet")
        git("add", str(self.index))
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
            "commit", "--quiet", "-m", "Synthetic source")
        source, tree = git("rev-parse", "HEAD", "HEAD^{tree}").splitlines()
        self.dex.unlink()
        sdk = self.root / "sdk/build-tools/36.0.0"
        aapt = sdk / "aapt"
        aapt.write_text("#!/bin/sh\ncat <<'END'\n"
                        "package: name='ru.natro.statuswidget' versionCode='208021385' versionName='3.5.2'\n"
                        "sdkVersion:'28'\ntargetSdkVersion:'28'\napplication-label:'Natro'\nEND\n")
        aapt.chmod(0o755)
        signer = sdk / "apksigner"
        signer.write_text("#!/bin/sh\necho 'ERROR: Missing META-INF/MANIFEST.MF' >&2\nexit 1\n")
        signer.chmod(0o755)
        with zipfile.ZipFile(self.apk, "a") as apk:
            apk.writestr("META-INF/version-control-info.textproto",
                         f'repositories {{\n  system: GIT\n  revision: "{source}"\n}}\n')
        self.out = self.root / "natro-3.5.2-candidate"
        return {"NATRO_STANDALONE": "true", "VERSION_NAME": "3.5.2", "VERSION_CODE": "208021385",
                "NATRO_SOURCE_SHA": source, "NATRO_SOURCE_TREE": tree}

    def test_standalone_uses_checked_out_source_without_companion_builds(self):
        env = self.standalone_environment()
        with patch.dict(os.environ, env), contextlib.redirect_stdout(io.StringIO()):
            stage_natro_adb_candidate.main()
        result = json.loads((self.out / "candidate.json").read_text())
        self.assertTrue(result["standalone"])
        self.assertEqual(env["NATRO_SOURCE_SHA"], result["sourceCommit"])
        self.assertEqual(env["NATRO_SOURCE_TREE"], result["sourceTree"])
        self.assertEqual("a" * 40, result["orchestratorCommit"])
        self.assertEqual("123", result["ciRunId"])
        self.assertEqual(1906, result["unitTests"]["tests"])
        self.assertNotIn("navigator", result)
        self.assertFalse(result["helperChanged"])
        self.assertEqual({"candidate.json", "Natro-3.5.2-unsigned.apk", "tools", "test-results"},
                         {p.name for p in self.out.iterdir()})
        self.assertEqual(self.apk.read_bytes(), (self.out / "Natro-3.5.2-unsigned.apk").read_bytes())

    def test_standalone_rejects_unverified_source_and_version(self):
        env = self.standalone_environment()
        for key, invalid in (("NATRO_SOURCE_SHA", "a"*40), ("NATRO_SOURCE_TREE", "a"*40),
                             ("VERSION_CODE", "208021384"), ("VERSION_NAME", "3.5.1")):
            with self.subTest(key=key), patch.dict(os.environ, {**env, key: invalid}):
                with self.assertRaises(ValueError):
                    stage_natro_adb_candidate.main()
                self.assertFalse(self.out.exists())

    def test_standalone_checks_dirty_inputs_assets_and_skipped_tests(self):
        env = self.standalone_environment()
        with patch.dict(os.environ, env):
            extra = self.index.with_name("untracked-input")
            extra.write_text("not part of the requested tree")
            with self.assertRaisesRegex(ValueError, "untracked build inputs"):
                stage_natro_adb_candidate.main()
            extra.unlink()
            self.report.write_text('<testsuite tests="1906" failures="0" errors="0" skipped="1"/>')
            with self.assertRaisesRegex(ValueError, "Full test suite"):
                stage_natro_adb_candidate.main()
            self.report.write_text('<testsuite tests="1906" failures="0" errors="0" skipped="0"/>')
            self.write_apk(missing="lan/index.html")
            with self.assertRaises(KeyError):
                stage_natro_adb_candidate.main()
            self.assertFalse(self.out.exists())

    def test_standalone_validates_embedded_source_apk_identity_and_unsigned_state(self):
        env = self.standalone_environment()
        sdk = self.root / "sdk/build-tools/36.0.0"
        signer = sdk / "apksigner"
        original_signer = signer.read_text()
        with patch.dict(os.environ, env):
            for code, error in ((0, "must be unsigned"), (2, "SDK could not confirm")):
                signer.write_text(f"#!/bin/sh\nexit {code}\n")
                with self.subTest(code=code), self.assertRaisesRegex(ValueError, error):
                    stage_natro_adb_candidate.main()
                self.assertFalse(self.out.exists())
            signer.write_text(original_signer)
            aapt = sdk / "aapt"
            original_aapt = aapt.read_text()
            aapt.write_text(original_aapt.replace("208021385", "208021384"))
            with self.assertRaisesRegex(ValueError, "APK identity"):
                stage_natro_adb_candidate.main()
            aapt.write_text(original_aapt)
            self.write_apk()
            with zipfile.ZipFile(self.apk, "a") as apk:
                apk.writestr("META-INF/version-control-info.textproto", 'revision: "' + "a"*40 + '"')
            with self.assertRaisesRegex(ValueError, "APK Git revision"):
                stage_natro_adb_candidate.main()
            self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
