"""Execute real trace parsing and shell failures in isolated fixtures, without vehicle files."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HudLcaDiagnosticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        harness = cls.root / 'HudTraceReplay.java'
        harness.write_text(r'''
import dezz.status.widget.adb.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class HudTraceReplay {
 static void check(boolean yes){if(!yes)throw new AssertionError();}
 public static void main(String[] args){
  if(args.length>0){System.out.print(HudLcaPatch.install(HudLcaPatch.Mode.valueOf(args[0])));return;}
  List<String> events=new ArrayList<>();
  HudLcaDiagnostics trace=new HudLcaDiagnostics(events::add);
  String text="password=never-copy-this\nNATRO_HUD_DIAG stage=module_metadata\r\n"
    +"NATRO_HUD_DIAG metadata=0:0:600\nNATRO_HUD_DIAG stage=unknown_secret\n"
    +"NATRO_HUD_DIAG stage=module_context password=never-copy-this\n"
    +"NATRO_HUD_DIAG exit=1 stage=module_metadata\n";
  for(byte b:text.getBytes(StandardCharsets.UTF_8))trace.accept(new byte[]{b});
  check(events.size()==3);check(trace.summary().contains("exit=1 stage=module_metadata"));
  check(!events.toString().contains("never-copy-this"));check(!events.toString().contains("unknown_secret"));
  trace.accept("NATRO_HUD_DIAG stage=remount_rw".getBytes(StandardCharsets.UTF_8));
  check(trace.summary().contains("partial_line=true"));check(events.size()==3);
  trace.accept(("x".repeat(10000)+"\nNATRO_HUD_DIAG stage=completed\n").getBytes(StandardCharsets.UTF_8));
  check(events.size()==4);check(trace.summary().contains("shell_stage=completed"));
  for(int i=0;i<300;i++)trace.accept("NATRO_HUD_DIAG stage=target_install\n".getBytes(StandardCharsets.UTF_8));
  check(events.size()==128);
  trace.accept("NATRO_HUD_DIAG exit=44 stage=rollback\n".getBytes(StandardCharsets.UTF_8));
  check(events.size()==129);check(trace.summary().contains("exit=44 stage=rollback"));
  HudLcaDiagnostics brokenSink=new HudLcaDiagnostics(e->{throw new IllegalStateException();});
  brokenSink.accept("NATRO_HUD_DIAG stage=module_context\n".getBytes(StandardCharsets.UTF_8));
  check(brokenSink.summary().contains("diagnostic_sink_failures=1"));
  check(brokenSink.summary().contains("shell_stage=module_context"));
  List<String> blockEvents=new ArrayList<>();
  HudLcaDiagnostics blockTrace=new HudLcaDiagnostics(blockEvents::add);
  blockTrace.accept(("NATRO_HUD_DIAG stage=block_suffix\n"
    +"NATRO_HUD_DIAG block_error=no_space rc=7\n"
    +"dd: private path or secret must not be exported\n"
    +"NATRO_HUD_DIAG block_error=secret rc=7\n"
    +"NATRO_HUD_DIAG exit=7 stage=block_suffix\n").getBytes(StandardCharsets.UTF_8));
  check(blockEvents.size()==3);check(blockEvents.get(1).equals("block_error=no_space rc=7"));
  check(!blockEvents.toString().contains("secret"));
 }
}''')
        sources = [ROOT/'app/src/main/java/dezz/status/widget/adb'/name
                   for name in ('HudLcaPatch.java', 'HudLcaDiagnostics.java')]
        result = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(cls.root),
                                 *map(str, sources), str(harness)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_fragmented_partial_and_excessive_output_keeps_only_allowed_evidence(self):
        subprocess.run(['java', '-cp', str(self.root), 'HudTraceReplay'], check=True)

    def test_every_install_script_has_valid_shell_syntax(self):
        for mode in ('ORIGINAL', 'SIMPLE', 'GUIDE', 'AR'):
            script = subprocess.check_output(['java', '-cp', str(self.root), 'HudTraceReplay', mode], text=True)
            result = subprocess.run(['sh', '-n'], input=script, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_silent_exit_one_names_exact_guard_before_any_write(self):
        script = subprocess.check_output(['java', '-cp', str(self.root), 'HudTraceReplay', 'GUIDE'], text=True)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            module = root/'module.so'
            module.write_bytes(bytes(268424))
            script = script.replace('/vendor/lib64/vendor.ecarx.xma.automotive.vehicle@1.0-modules.so', str(module))
            backup = root/'must-not-exist'
            script = script.replace('/data/local/tmp/natro-hud-lca', str(backup))
            fake = root/'bin'
            fake.mkdir()
            stubs = {
                'getprop': 'case "$1" in ro.build.version.sdk) echo 28;; *) echo arm64-v8a;; esac',
                'id': 'echo 0',
                'sha256sum': 'echo b5bcf37a0aa8a31bf69296989ba7730ded54f99998f43a6313af4e4a4180cca6',
                'od': 'echo 820000d0830000d0423c289163801991e0070032e1031faae4031a2a2fd4ff97',
                'stat': 'echo "${FIXTURE_METADATA:-0:0:600}"',
                'ls': 'echo no_selinux_context',
            }
            for name, body in stubs.items():
                tool = fake/name
                tool.write_text('#!/bin/sh\n'+body+'\n')
                tool.chmod(0o755)
            env = dict(os.environ, PATH=str(fake)+os.pathsep+os.environ['PATH'])
            for metadata, expected in (('0:0:600', 'module_metadata'), ('0:0:644', 'module_context')):
                env['FIXTURE_METADATA'] = metadata
                result = subprocess.run(['sh', '-c', script], env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode, 1, result.stdout+result.stderr)
                self.assertIn('NATRO_HUD_DIAG exit=1 stage='+expected, result.stdout)
                self.assertNotIn('NATRO_HUD_PATCH_VERIFIED_', result.stdout)
                self.assertFalse(backup.exists())
                self.assertEqual(module.read_bytes(), bytes(268424))


if __name__ == '__main__':
    unittest.main()
