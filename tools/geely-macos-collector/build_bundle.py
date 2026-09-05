#!/usr/bin/env python3
"""Deterministic Mac bundle: own code and derived metadata only."""
import hashlib
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parent
NAME='KX11-Missing-Files-macOS-v1.0.0'
FILES=['Collect-KX11.command','collect_missing.py','collection_plan.json','known_files.json.gz',
       'README_RU.md','PROVENANCE.json','VALIDATION.json','VALIDATION_BASELINE.json',
       'build_known_baseline.py','build_bundle.py']

def build():
    paths=[ROOT/p for p in FILES]
    paths+=sorted((ROOT/'lib').rglob('*.py'))+sorted((ROOT/'tests').rglob('*.py'))
    assert all(p.is_file() for p in paths)
    dest=ROOT.parents[1]/'docs/geely-kx11/downloads'
    dest.mkdir(parents=True,exist_ok=True)
    target=dest/(NAME+'.zip')
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as out:
        for p in sorted(paths):
            info=zipfile.ZipInfo(NAME+'/'+p.relative_to(ROOT).as_posix(),date_time=(2026,9,5,0,0,0))
            info.create_system=3
            info.external_attr=(0o100755 if p.name=='Collect-KX11.command' else 0o100644)<<16
            info.compress_type=zipfile.ZIP_DEFLATED
            out.writestr(info,p.read_bytes())
    digest=hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.zip.sha256').write_text(digest+'  '+target.name+'\n')
    print(target)
    print(digest)
    return target

if __name__=='__main__':build()
