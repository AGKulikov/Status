"""Run the production read-only inspection against isolated files, never an installed module."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HudLcaInspectionTest(unittest.TestCase):
    def test_rejected_modules_have_a_specific_reason_and_cannot_unlock_installation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            harness = root / 'Inspect.java'
            harness.write_text('''
import dezz.status.widget.adb.HudLcaPatch;
public class Inspect {
 public static void main(String[] args) {
  for(HudLcaPatch.Mode mode:HudLcaPatch.Mode.values())
   if(HudLcaPatch.inspectedMode("Report\\nNATRO_HUD_INSPECT_VERIFIED_"+mode.name()+"\\r\\n")!=mode)
    throw new AssertionError("Complete mode result rejected");
  for(String text:new String[]{"", "Проверка завершена", "NATRO_HUD_INSPECT_VERIFIED_FUTURE",
      "NATRO_HUD_INSPECT_VERIFIED_SIMPLE trailing", "NATRO_HUD_INSPECT_VERIFIED_SIMPLE\\nNATRO_HUD_INSPECT_VERIFIED_AR"})
   if(HudLcaPatch.inspectedMode(text)!=null)throw new AssertionError("Incomplete/ambiguous result accepted");
  System.out.print(HudLcaPatch.inspect());
 }
}''')
            source = ROOT/'app/src/main/java/dezz/status/widget/adb/HudLcaPatch.java'
            subprocess.run(['java','com.sun.tools.javac.Main','-d',temp,str(source),str(harness)],check=True,capture_output=True)
            command = subprocess.check_output(['java','-cp',temp,'Inspect'],text=True)
            module = 'vendor.ecarx.xma.automotive.vehicle@1.0-modules.so'
            primary, legacy = root/'primary.so', root/'legacy.so'
            command = command.replace('/system/vendor/lib64/'+module, str(legacy))
            command = command.replace('/vendor/lib64/'+module, str(primary))
            command = command.replace('/data/local/tmp/natro-hud-lca', str(root/'never-created'))
            tools = root/'bin'; tools.mkdir()
            prop = tools/'getprop'
            prop.write_text('#!/bin/sh\ncase "$1" in ro.build.version.sdk) echo "${TEST_SDK:-28}";; ro.product.cpu.abi) echo arm64-v8a;; esac\n')
            prop.chmod(0o755)
            env = dict(os.environ, PATH=str(tools)+os.pathsep+os.environ['PATH'])

            def rejected(code, reason):
                before = {p.name:p.read_bytes() for p in (primary,legacy) if p.is_file()}
                result = subprocess.run(['sh','-c',command],env=env,capture_output=True,text=True)
                self.assertEqual(result.returncode,code,result.stdout+result.stderr)
                self.assertIn(reason,result.stdout)
                self.assertNotIn('NATRO_HUD_INSPECT_VERIFIED_',result.stdout)
                self.assertFalse((root/'never-created').exists())
                self.assertEqual(before,{p.name:p.read_bytes() for p in (primary,legacy) if p.is_file()})

            rejected(45,'Модуль не найден')
            primary.write_bytes(b'unknown')
            rejected(49,'Неизвестный размер')
            env['TEST_SDK']='29'
            rejected(41,'Неподдерживаемая платформа')
            env.pop('TEST_SDK')
            primary.unlink(); legacy.write_bytes(b'x'); primary.symlink_to(legacy)
            rejected(46,'символической ссылкой')
            primary.unlink(); legacy.write_bytes(bytes(268424))
            rejected(40,'Неизвестная версия')


if __name__ == '__main__':
    unittest.main()
