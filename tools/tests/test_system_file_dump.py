"""Production collector replay and actual ICU syntax regression; no device access."""
import ctypes
import ctypes.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/dezz/status/widget'

class SystemDumpTest(unittest.TestCase):
    def test_graphics_evidence_replay(self):
        with tempfile.TemporaryDirectory() as work:
            files=[SOURCE/'diagnostics/SystemFileDump.java',SOURCE/'diagnostics/QnxFileReader.java',
                   SOURCE/'diagnostics/GraphicsEvidence.java',SOURCE/'adb/AdbShellResult.java',
                   ROOT/'tools/tests/java/GraphicsEvidenceReplay.java']
            run=subprocess.run(['java','com.sun.tools.javac.Main','-d',work,*map(str,files)],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            run=subprocess.run(['java','-cp',work,'dezz.status.widget.diagnostics.GraphicsEvidenceReplay',work],capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_qnx_protocol_replay(self):
        with tempfile.TemporaryDirectory() as work:
            compiled=subprocess.run(['java','com.sun.tools.javac.Main','-d',work,
                str(SOURCE/'diagnostics/QnxFileReader.java'),str(ROOT/'tools/tests/java/QnxFileReaderReplay.java')],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-cp',work,'dezz.status.widget.diagnostics.QnxFileReaderReplay'],capture_output=True,text=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_production_owner_parser(self):
        with tempfile.TemporaryDirectory() as work:
            run=subprocess.run(['java','com.sun.tools.javac.Main','-d',work,
                str(SOURCE/'instrument/InstrumentDisplayOwner.java'),str(ROOT/'tools/tests/java/InstrumentOwnerReplay.java')],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            run=subprocess.run(['java','-cp',work,'dezz.status.widget.instrument.InstrumentOwnerReplay'],capture_output=True,text=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
    def test_production_binary_collector(self):
        with tempfile.TemporaryDirectory() as work:
            files = [SOURCE/'diagnostics/SystemFileDump.java', SOURCE/'diagnostics/QnxFileReader.java', SOURCE/'diagnostics/GraphicsEvidence.java', SOURCE/'adb/AdbShellResult.java',
                     ROOT/'tools/tests/java/SystemFileDumpReplay.java']
            compiled = subprocess.run(['java','com.sun.tools.javac.Main','-d',work,*map(str,files)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run = subprocess.run(['java','-cp',work,'dezz.status.widget.diagnostics.SystemFileDumpReplay',work],capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            self.assertIn('PASS',run.stdout)

    def test_actual_icu_rejects_old_brace_and_accepts_production_patterns(self):
        name=ctypes.util.find_library('icui18n')
        if not name: self.skipTest('native ICU unavailable; Android/device gate remains open')
        lib=ctypes.CDLL(name)
        suffix=re.search(r'\.so\.(\d+)',name)
        def symbol(name):
            try: return getattr(lib,name)
            except AttributeError: return getattr(lib,name+'_'+suffix.group(1))
        open_regex=symbol('uregex_open'); close=symbol('uregex_close')
        open_regex.restype=ctypes.c_void_p
        open_regex.argtypes=[ctypes.c_void_p,ctypes.c_int32,ctypes.c_uint32,ctypes.c_void_p,ctypes.POINTER(ctypes.c_int32)]
        close.argtypes=[ctypes.c_void_p]
        def compile_pattern(pattern):
            encoded=pattern.encode('utf-16-le'); buffer=ctypes.create_string_buffer(encoded)
            status=ctypes.c_int32(0)
            compiled=open_regex(buffer,len(encoded)//2,0,None,ctypes.byref(status))
            if compiled: close(compiled)
            return status.value
        source=(SOURCE/'instrument/InstrumentDisplayOwner.java').read_text()
        literals=re.findall(r'Pattern.compile\(("(?:\\.|[^"\\])*")\)',source)
        self.assertEqual(len(literals),2)
        patterns=[json.loads(literal) for literal in literals]
        for pattern in patterns: self.assertEqual(compile_pattern(pattern),0,pattern)
        old=patterns[1].removesuffix(r'\}')+'}'
        self.assertGreater(compile_pattern(old),0,'Must reproduce ICU-only production crash')

    def test_no_vehicle_writes_and_explicit_separate_export(self):
        source=(SOURCE/'diagnostics/SystemFileDump.java').read_text()
        ui=(SOURCE/'DiagnosticsActivity.java').read_text()
        self.assertNotIn('daemonRoot(',source+ui)
        self.assertNotIn('/data/',source)
        self.assertIn('compareAndSet(false, true)',ui)
        self.assertIn('systemDumpSession.close()',ui)
        self.assertIn('systemDumpSession.cancel(',ui)
        self.assertIn('Отправить дамп ZIP',ui)
        self.assertNotIn('SystemFileDump', (SOURCE/'diagnostics/DiagnosticBundle.java').read_text())

    def test_per_file_stream_and_scoped_dump_deadline(self):
        transport=(SOURCE/'shell/AdbTransport.java').read_text()
        method=transport.split('public void collectQnx(',1)[1].split('public String execLongRunning',1)[0]
        self.assertIn('for (String path : dezz.status.widget.diagnostics.QnxFileReader.TARGETS)',method)
        self.assertIn('.collectSingle(path, receiver)',method)
        self.assertEqual(method.count('openBounded("shell:toybox nc -w 30 198.18.34.2 8000")'),2)
        self.assertIn('.collectProcesses(receiver)',method)
        self.assertIn('60_000L',method)
        session=(SOURCE/'adb/AdbConsoleSession.java').read_text()
        self.assertIn('return submitBounded(operation,listener,60);',session)
        self.assertIn('return submitBounded(operation,listener,600);',session)
        self.assertIn('session.submitSystemDump(', (SOURCE/'DiagnosticsActivity.java').read_text())

if __name__ == '__main__': unittest.main()
