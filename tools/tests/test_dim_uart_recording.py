"""Exercise the actual append-only evidence writer; no automobile or SDK needed."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class DimUartRecordingTest(unittest.TestCase):
    def test_capture_owner_and_cancellation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = ROOT / 'app/src/main/java/dezz/status/widget'
            sources = list((ROOT / 'tools/tests/runtime_stubs').rglob('*.java'))
            sources += [source / (name + '.java') for name in (
                'diagnostics/DimUartCapture', 'diagnostics/DimUartRecording',
                'diagnostics/SystemFileDump', 'diagnostics/GraphicsEvidence', 'diagnostics/QnxFileReader',
                'adb/AdbConsoleSession', 'adb/AdbShellResult')]
            sources.append(ROOT / 'tools/tests/java/DimUartCaptureReplay.java')
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', directory, *map(str, sources)], check=True)
            subprocess.run(['java', '-cp', directory, 'dezz.status.widget.diagnostics.DimUartCaptureReplay', directory],
                           check=True, timeout=15)

    def test_durable_partial_and_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            sources = [ROOT / 'app/src/main/java/dezz/status/widget/diagnostics/DimUartRecording.java',
                       ROOT / 'tools/tests/java/DimUartRecordingReplay.java']
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', directory, *map(str, sources)], check=True)
            subprocess.run(['java', '-cp', directory, 'dezz.status.widget.diagnostics.DimUartRecordingReplay', directory],
                           check=True, timeout=30)
