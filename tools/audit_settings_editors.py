#!/usr/bin/env python3
"""Inventory the actual editor sources, field destinations and nested forms.

The checked-in catalog is consumed by Android. --check catches a new unindexed editor/field;
the inventory is evidence of scope, never a substitute for interaction/screenshot tests.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/dezz/status/widget'
EXTRA = {'MainActivity.java', 'LauncherActivity.java', 'BrickListAdapter.java', 'ViewBinder.java',
         'ShortcutActionPicker.java', 'SmartHomeShortcutPicker.java', 'InformationSourcePicker.java',
         'AppleColorPickerDialog.java', 'VectorIconPickerDialog.java', 'PhoneAppIconsActivity.java'}
METHOD = re.compile(r'^\s*(?:@[^\n]+\s+)?(?:public|private|protected)\s+(?:(?:static|final|synchronized)\s+)*'
                    r'[\w<>.?\[\], @]+?\s+(\w+)\s*\(', re.M)
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')

def sources():
    return [p for p in sorted(JAVA.rglob('*.java'))
            if ('SettingsActivity' in p.name or 'EditorActivity' in p.name or p.name in EXTRA)
            and not any(part in {'servicemode', 'drivemode'} for part in p.parts)]

def destination(label):
    # These rules generate a reviewable, exact-label map. Android never infers by keywords.
    text = label.lower().replace('ё', 'е')
    if re.search(r'json|токен|парол|порт\b|адрес сервера|url|id подключения|id цели|id телеметрии|entity|topic|characteristic|отлад|диагност|сброс|экспорт|импорт|расширенн|дополнительн|техническ', text): return 'ADVANCED'
    if re.search(r'цвет|шрифт|фон|непрозрач|прозрач|скругл|контур|тень|оформлен|жирн|курсив|насыщен|палитр|градиент|оттенок|яркость|стиль|обводк', text): return 'APPEARANCE'
    if re.search(r'положен|ширин|высот|отступ|размер|масштаб|координат|сетка|сетке|ячей|ячеек|колонк|столб|слева|справа|сверху|снизу|выравнив|интервал|поворот|геометр|компоновк', text): return 'POSITION'
    if re.search(r'действ|нажат|автозап|после загрузки|тайм|задерж|поведен|скрыва|жест|при касании|отклик|анимац|порог|ожидание|повтор|длительн|услови|событи|предикат|триггер', text): return 'BEHAVIOR'
    if re.search(r'элемент|добав|удал|состав|порядок|иконк|значок|показыва|видимость|плитк|содержимое|каталог|список|источник|строка [123]|обложк', text): return 'CONTENT'
    return 'MAIN'

def build():
    fields = {}
    inventory = []
    for path in sources():
        source = path.read_text()
        package = re.search(r'package\s+([\w.]+);', source).group(1)
        labels = set()
        for match in STRING.finditer(source):
            try: text = json.loads('"' + match[1] + '"').strip()
            except ValueError: continue
            if re.search('[а-яА-ЯЁё]', text) and 3 <= len(text) <= 140 and '\n' not in text:
                labels.add(text)
                fields[text] = destination(text)
        methods = list(METHOD.finditer(source))
        forms = []
        for index, match in enumerate(methods):
            end = methods[index + 1].start() if index + 1 < len(methods) else len(source)
            body = source[match.start():end]
            if any(token in body for token in ('DialogBuilder(', 'AlertDialog.Builder(', 'setContentView(', 'new ScrollView(', 'new NestedScrollView(')):
                forms.append({'method': match[1], 'line': source[:match.start()].count('\n') + 1,
                              'dialogs': body.count('DialogBuilder(') + body.count('AlertDialog.Builder('),
                              'numeric_controls': body.count('SettingsSeekBar(')})
        inventory.append({'path': str(path.relative_to(ROOT)), 'class': package + '.' + path.stem,
                          'sha256': hashlib.sha256(source.encode()).hexdigest(), 'forms': forms,
                          'labels': sorted(labels), 'numeric_controls': source.count('SettingsSeekBar(')})
    return fields, {'schema': 1, 'evidence': 'source inventory; Android interactions and screenshots are separate',
                    'editors': inventory}

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    fields, inventory = build()
    for path, data in [(ROOT/'app/src/main/assets/settings-editor-fields.json', fields),
                       (ROOT/'docs/settings/editor-inventory.json', inventory)]:
        value = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + '\n'
        if args.check:
            if not path.exists() or path.read_text() != value: raise SystemExit(f'Stale settings inventory: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value)
    print(f'{len(inventory["editors"])} editor sources, {sum(len(e["forms"]) for e in inventory["editors"])} form methods, {len(fields)} field labels')

if __name__ == '__main__': main()
