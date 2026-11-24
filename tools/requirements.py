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


def revision(text):
    return hashlib.sha256(text.encode()).hexdigest()[:16]


def sections(text):
    lines = text.splitlines(keepends=True)
    starts = [0] + [i for i, line in enumerate(lines) if i and re.match(r'^#{2,6} ', line)]
    chunks, parents = [], []
    for n, (a, b) in enumerate(zip(starts, starts[1:]+[len(lines)])):
        heading = re.match(r'^(#{1,6}) ', lines[a])
        level = len(heading[1]) if heading else 1
        while parents and parents[-1]['level'] >= level:
            parents.pop()
        chunk = dict(key=f'S{n:03}', start=a+1, end=b, level=level,
                     title=lines[a].strip().lstrip('# ').strip(),
                     parents=[p['key'] for p in parents],
                     text=''.join(lines[a:b]))
        chunks.append(chunk)
        parents.append(chunk)
    return chunks


def fold(text):
    return text.casefold().replace('ё', 'е')


def matching_lines(section, terms):
    needles = [fold(term) for term in terms]
    return [(offset, line) for offset, line in enumerate(section['text'].splitlines())
            if any(term in fold(line) for term in needles)]


def excerpt(line, terms, width=180):
    folded = fold(line)
    positions = [folded.find(fold(t)) for t in terms if fold(t) in folded]
    start = max(0, min(positions, default=0)-50)
    return ('…' if start else '') + line[start:start+width] + ('…' if start+width < len(line) else '')


def resolve_ref(chunks, reference, text):
    key, separator, stamp = reference.partition('@')
    if not separator or stamp != revision(text):
        raise ValueError('STALE or unversioned section reference; run search again and copy Snnn@revision')
    chosen = next((s for s in chunks if s['key'] == key), None)
    if not chosen:
        raise ValueError('Unknown section; run search again')
    return chosen


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


def check_generated(text, outputs=None):
    outputs = generated(text) if outputs is None else outputs
    for name, value in outputs.items():
        p = OUT/name
        if not p.exists() or p.read_text() != value:
            raise ValueError(f'STALE or missing: {p}; run python tools/requirements.py build')
    expected = {str(OUT/name) for name in outputs if name.startswith('modules/')}
    if {str(p) for p in (OUT/'modules').glob('*.md')} != expected:
        raise ValueError('Unexpected module file; run build')
    routing = (OUT/'README_RU.md').read_text()
    for name in outputs:
        if name.startswith('modules/') and f']({name})' not in routing:
            raise ValueError(f'No reading route for {name}; update docs/requirements/README_RU.md')
    if ''.join(s['text'] for s in sections(text)) != text:
        raise ValueError('Incomplete section coverage')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('build')
    sub.add_parser('check')
    search = sub.add_parser('search')
    search.add_argument('terms', nargs='+', help='Case-insensitive literal OR; е/ё equivalent')
    read = sub.add_parser('read')
    read.add_argument('section', help='Copy Snnn@revision from search, never reuse after edits')
    matches = sub.add_parser('matches')
    matches.add_argument('section')
    matches.add_argument('terms', nargs='+')
    for command, limit in ((search, 30), (read, 100), (matches, 20)):
        command.add_argument('--offset', type=int, default=0)
        command.add_argument('--limit', type=int, default=limit)
    args = parser.parse_args(argv)
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
            try:
                check_generated(text, outputs)
            except ValueError as error:
                parser.error(str(error))
        print(f'{args.command}: {len(outputs)-2} modules, {sum(bool(ROW.match(l)) for l in text.splitlines())} canonical rows; full section coverage OK')
        return
    if args.offset < 0 or args.limit < 1:
        parser.error('offset must be >= 0 and limit >= 1')
    if hasattr(args, 'terms') and any(not term.strip() for term in args.terms):
        parser.error('Search terms must be nonempty')
    try:
        check_generated(text)
        chunks = sections(text)
        chosen = resolve_ref(chunks, args.section, text) if hasattr(args, 'section') else None
    except ValueError as error:
        parser.error(str(error))
    stamp = revision(text)
    if args.command == 'search':
        hits = [(s, matching_lines(s, args.terms)) for s in chunks]
        hits = [(s, lines) for s, lines in hits if lines]
        if hits and args.offset >= len(hits):
            parser.error('Offset outside search results; nothing inspected')
        page = hits[args.offset:args.offset+args.limit]
        print(f'{len(hits)} matching sections; showing {args.offset+1 if page else 0}..{args.offset+len(page)}; revision {stamp}. OR search; snippets are not full requirements.')
        for s, lines in page:
            print(f"{s['key']}@{stamp} L{s['start']}-{s['end']} {s['title']}")
            offset, line = lines[0]
            print(f"  {len(lines)} matching lines; first L{s['start']+offset}, read --offset {offset}: {excerpt(line, args.terms)}")
        if args.offset+args.limit < len(hits):
            print(f'REMAINING: use search with same terms and --offset {args.offset+args.limit}')
        if not hits:
            print('NO MATCH is not proof of absence: search family IDs, synonyms, and linked specifications; record the gap.')
    else:
        parent_refs = [f'{key}@{stamp}' for key in chosen['parents']]
        print(f"{chosen['key']}@{stamp}; parent context: {', '.join(parent_refs) or 'none'}")
        if args.command == 'matches':
            hits = matching_lines(chosen, args.terms)
            if hits and args.offset >= len(hits):
                parser.error('Offset outside matching lines; nothing inspected')
            page = hits[args.offset:args.offset+args.limit]
            print(f'{len(hits)} matching lines; showing {args.offset+1 if page else 0}..{args.offset+len(page)}. Read bodies around these offsets; snippets are not acceptance evidence.')
            if not hits:
                print('NO MATCH is not proof of absence: broaden the query and inspect context.')
            for offset, line in page:
                print(f"L{chosen['start']+offset}, read --offset {max(0, offset-3)}: {excerpt(line, args.terms)}")
            if args.offset+args.limit < len(hits):
                print(f'REMAINING: use matches with same terms and --offset {args.offset+args.limit}')
        else:
            lines = chosen['text'].splitlines()
            print(f"{len(lines)} lines; showing offset {args.offset}, limit {args.limit}; original links resolve from repository root.")
            for i, line in enumerate(lines[args.offset:args.offset+args.limit], chosen['start']+args.offset):
                print(f'{i}: {line}')
            if args.offset+args.limit < len(lines):
                print(f'REMAINING: use read --offset {args.offset+args.limit}')
            if args.offset >= len(lines):
                parser.error('Offset outside section; nothing read')


if __name__ == '__main__':
    main()
