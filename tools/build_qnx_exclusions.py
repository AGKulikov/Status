#!/usr/bin/env python3
"""Verify both private user archives; emit only path fingerprints/size/content SHA.

Never copy proprietary payloads or private path names to the public source tree.
Exclusions mean already downloaded, not proof that current remote bytes match.
"""
import argparse
import hashlib
from pathlib import Path
import zipfile


def digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        h.update(block)
    return h.hexdigest()


def build(full, partial):
    records = {}
    archives = []
    for archive, label in [(partial, 'partial'), (full, 'full')]:
        with archive.open('rb') as stream:
            archives.append((label, digest(stream)))
        with zipfile.ZipFile(archive) as z:
            if len(z.namelist()) != len(set(z.namelist())):
                raise ValueError('duplicate ZIP entry')
            for line in z.read('manifest.tsv').decode('utf-8').splitlines()[1:]:
                parts = line.split('\t')
                if len(parts) != 4:
                    raise ValueError('invalid manifest row')
                name, status, size, sha = parts
                if label == 'full' and name.startswith('qnx/tree/'):
                    path, entry = name[len('qnx/tree'):], name
                elif label == 'partial' and name.startswith('qnx:/'):
                    path = name[4:]
                    entry = 'qnx/files' + path
                else:
                    continue
                if status != 'collected_double_read':
                    continue
                if '\\' in path or any(ord(c) < 32 for c in path):
                    raise ValueError('unsupported escaped path')
                info = z.getinfo(entry)
                with z.open(info) as stream:
                    actual = digest(stream)
                if info.file_size != int(size) or actual != sha:
                    raise ValueError('payload differs from manifest')
                if path.startswith('/var/log/') or (path.startswith('/userdata/DLT/') and not path.endswith('.zip')):
                    continue
                key = hashlib.sha256(path.encode()).hexdigest()
                old = records.get(key)
                source = 'both' if old and old[:2] == (size, sha) else label
                records[key] = (size, sha, source)
    header = ['# Verified archive SHA256: ' + label + '=' + sha for label, sha in archives]
    header += ['# SHA256(UTF-8 path)\tbytes\tarchived_sha256\tsource; current bytes are NOT revalidated']
    return '\n'.join(header + ['\t'.join((key, *value)) for key, value in sorted(records.items())]) + '\n'


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--full', type=Path, required=True)
    p.add_argument('--partial', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    content = build(a.full, a.partial)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(content)
    print('Verified exclusions:', len([line for line in content.splitlines() if not line.startswith('#')]))
