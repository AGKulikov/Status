"""Execute the production root wrapper against UID/su doubles in a real POSIX shell."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT


class RootButtonCommandTest(unittest.TestCase):
    def test_root_adbd_and_shell_uid_preserve_exact_command(self):
        source = (ROOT / 'app/src/main/java/dezz/status/widget/media/MediaButtonController.java').read_text()
        java = 'public class Wrapper {' + method(source, 'static String rootCommand(') + method(source, 'public static String quote(') + 'public static void main(String[] a){System.out.print(rootCommand(a[0]));}}'
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            (folder / 'Wrapper.java').write_text(java)
            subprocess.run(['java', 'com.sun.tools.javac.Main', str(folder / 'Wrapper.java')], check=True)
            for uid in ('0', '2000'):
                with self.subTest(uid=uid):
                    (folder / 'id').write_text('#!/bin/sh\nprintf "%s\\n" ' + uid + '\n')
                    (folder / 'su').write_text('#!/bin/sh\nprintf "%s\\n" "$1" > "$SU_TRACE"\n[ "$1" = 0 ] || exit 99\nshift\nexec "$@"\n')
                    for name in ('id', 'su'):
                        (folder / name).chmod(0o755)
                    trace = folder / 'su.trace'
                    trace.unlink(missing_ok=True)
                    # Metacharacters remain part of one shell argument, not a second execution.
                    payload = "printf '%s\\n' 'text with spaces' \"quote'\" '$(false)' '`false`'"
                    wrapper = subprocess.check_output(['java', '-cp', temp, 'Wrapper', payload], text=True)
                    env = {**os.environ, 'PATH': temp + os.pathsep + os.environ['PATH'], 'SU_TRACE': str(trace)}
                    result = subprocess.run(['/bin/sh', '-c', wrapper], env=env, text=True, capture_output=True, check=True)
                    self.assertEqual(result.stdout, "text with spaces\nquote'\n$(false)\n`false`\n")
                    self.assertEqual(trace.exists(), uid != '0')
                    if trace.exists(): self.assertEqual(trace.read_text(), '0\n')
