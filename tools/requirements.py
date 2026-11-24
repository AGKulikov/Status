#!/usr/bin/env python3
"""Lossless module views and bounded lookup of the authoritative requirement ledger."""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / 'PROJECT_REQUIREMENTS_RU.md'
OUT = ROOT / 'docs/requirements'
ROW = re.compile(r'^\| ([A-Z]+-\d{3}) \|')


def sections(text):
    lines = text.splitlines(keepends=True)
    starts = [0] + [i for i, line in enumerate(lines) if i and re.match(r'^#{2,6} ', line)]
    return [dict(key=f'S{n:03}', start=a+1, end=b,
                 title=lines[a].strip().lstrip('# ').strip(), text=''.join(lines[a:b]))
            for n, (a, b) in enumerate(zip(starts, starts[1:]+[len(lines)]))]


def relocate(row):
    # Original ledger links resolve from repository root; module views are three levels down.
    return re.sub(r'(\]\()([^\s)]+)(\))',
                  lambda m: m[0] if re.match(r'^[a-zA-Z]+:|^/', m[2]) else
                  m[1] + ('../../../PROJECT_REQUIREMENTS_RU.md' if m[2].startswith('#') else '../../../') + m[2] + m[3], row)


def generated(text):
    groups = collections.defaultdict(list)
    index = {}
    for number, line in enumerate(text.splitlines(), 1):
        match = ROW.match(line)
        if not match:
            continue
        rid = match[1]
        if rid in index:
            raise ValueError(f'Duplicate canonical ID: {rid}')
        family = rid.split('-')[0]
        groups[family].append(line)
        index[rid] = dict(line=number, module=f'modules/{family}.md')
    output = {}
    for family, rows in sorted(groups.items()):
        output[f'modules/{family}.md'] = (
            f'# {family}: исходные требования\n\n'
            'Автоматическая выборка полных строк из [реестра](../../../PROJECT_REQUIREMENTS_RU.md). '
            'Статусы относятся к указанным в них датам; последующие уточнения могут менять правило. '
            'Перед работой прочитать [действующие уточнения](../../CURRENT_REQUIREMENTS_RU.md) '
            'и найти изменения по ID, семейству и теме через `python tools/requirements.py search`. '
            'Проза вне таблиц также содержит обязательные требования. Не редактировать эту выборку вручную.\n\n'
            '| ID | Требование | Статус / критерий |\n|---|---|---|\n' +
            '\n'.join(relocate(row) for row in rows) + '\n')
    common = [[cell.strip() for cell in line.split('|')[1:3]] for line in groups['REL']]
    output['COMMON_RU.md'] = ('# Обязательные общие правила\n\n'
        'Читать вместе с AGENTS.md и текущими уточнениями перед каждой новой задачей. '
        'Ниже дословно сохранены формулировки REL; исторические доказательства вынесены в '
        '[полные строки REL](modules/REL.md). При выпуске читать также их критерии и связанные уточнения.\n\n' +
        '\n'.join(f'- **{rid}** — {relocate(rule)}' for rid, rule in common) +
        '\n\nSOURCE, TEST, APK, PUBLISH и FIELD подтверждаются отдельно. Новая сборка не закрывает '
        'отрицательную приёмку на автомобиле. Более позднее явное решение заменяет конкретный пункт; '
        'положение записи и её возраст сами по себе не отменяют требования.\n')
    # COMMON is one directory higher than modules.
    output['COMMON_RU.md'] = output['COMMON_RU.md'].replace('../../../', '../../')
    output['index.json'] = json.dumps(dict(
        ledger_sha256=hashlib.sha256(text.encode()).hexdigest(), canonical=index,
        sections=[{k:v for k,v in s.items() if k != 'text'} for s in sections(text)]),
        ensure_ascii=False, indent=2) + '\n'
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('build')
    sub.add_parser('check')
    search = sub.add_parser('search')
    search.add_argument('terms', nargs='+', help='Literal case-insensitive OR across entire ledger')
    search.add_argument('--offset', type=int, default=0)
    search.add_argument('--limit', type=int, default=30)
    read = sub.add_parser('read')
    read.add_argument('section', help='Snnn from search; IDs are current ledger positions, not stable IDs')
    read.add_argument('--offset', type=int, default=0)
    read.add_argument('--limit', type=int, default=100)
    args = parser.parse_args()
    text = LEDGER.read_text()
    if args.command in ('build', 'check'):
        outputs = generated(text)
        if args.command == 'build':
            for name, value in outputs.items():
                p = OUT/name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(value)
            for p in (OUT/'modules').glob('*.md'):
                if str(p.relative_to(OUT)) not in outputs:
                    p.unlink()
        else:
            for name, value in outputs.items():
                p = OUT/name
                if not p.exists() or p.read_text() != value:
                    raise SystemExit(f'STALE or missing: {p.relative_to(ROOT)}; run build')
            expected = {str(OUT/name) for name in outputs if name.startswith('modules/')}
            if {str(p) for p in (OUT/'modules').glob('*.md')} != expected:
                raise SystemExit('Unexpected module file; run build')
            chunks = sections(text)
            assert ''.join(s['text'] for s in chunks) == text
        print(f'{args.command}: {len(outputs)-2} modules, {sum(bool(ROW.match(l)) for l in text.splitlines())} canonical rows; full section coverage OK')
        return
    if args.offset < 0 or args.limit < 1:
        parser.error('offset must be >= 0 and limit >= 1')
    chunks = sections(text)
    if args.command == 'search':
        hits = [s for s in chunks if any(t.casefold() in s['text'].casefold() for t in args.terms)]
        page = hits[args.offset:args.offset+args.limit]
        print(f'{len(hits)} matching sections; showing {args.offset+1 if page else 0}..{args.offset+len(page)}. OR search; titles only, read relevant bodies. Use --offset for remaining results.')
        for s in page:
            print(f"{s['key']} L{s['start']}-{s['end']} {s['title']}")
    else:
        chosen = next((s for s in chunks if s['key'] == args.section), None)
        if not chosen:
            parser.error('Unknown section; run search again after ledger changes')
        lines = chosen['text'].splitlines()
        print(f"{chosen['key']}: {len(lines)} lines; showing offset {args.offset}, limit {args.limit}; original links resolve from repository root.")
        for i, line in enumerate(lines[args.offset:args.offset+args.limit], chosen['start']+args.offset):
            print(f'{i}: {line}')
        if args.offset+args.limit < len(lines):
            print(f'REMAINING: use --offset {args.offset+args.limit}')


if __name__ == '__main__':
    main()
