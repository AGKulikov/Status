#!/usr/bin/env python3
"""Stage only our compiled DEX and provenance. Proprietary APK/key never enter CI."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / 'build/navigation-mod'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
if git('status', '--porcelain', '--untracked-files=no'):
    raise RuntimeError('Tracked tree changed during build')
manifest = {
    'sourceCommit': git('rev-parse', 'HEAD'),
    'sourceTree': git('rev-parse', 'HEAD^{tree}'),
    'ciRunId': os.environ['GITHUB_RUN_ID'],
    'versionName': 'RouteHistoryDiag-1',
    'natroRequiredVersion': 'existing 3.1.3; no Natro update',
    'navigator': {
        # Legacy signing-tool flag means this DEX replacement is authorized;
        # this experiment has no paired Natro APK.
        'pairRequired': True,
        'allowedPayloadChanges': ['classes19.dex'],
        'baselineSha256': 'b224bed5268469f620ab4bff3fec382aece868b9372450ff05f1f9176631280a',
        'patchSha256': hashlib.sha256((out / 'classes19.dex').read_bytes()).hexdigest(),
    },
    'physicalKx11Verification': 'pending',
    'crossDeviceSyncVerification': 'pending',
}
(out / 'candidate.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
