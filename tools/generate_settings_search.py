#!/usr/bin/env python3
"""Build a deterministic text index from actual Russian controls in settings sources."""
import json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def build():
    values=[]
    for path in sorted((ROOT/'app/src/main/java/dezz/status/widget').rglob('*.java')):
        if 'SettingsActivity' not in path.name and path.name not in {'MainActivity.java','PresetsActivity.java','PhoneAppIconsActivity.java'}:continue
        if '/servicemode/' in str(path) or '/drivemode/' in str(path):continue
        text=path.read_text();package=re.search(r'package\s+([\w.]+);',text)
        if not package:continue
        activity=package.group(1)+'.'+path.stem
        labels=set()
        for match in re.finditer(r'\b(?:setText|setHint|section|heading|label|title|button|toggle|number|slider|add\w*|labeled\w*)\s*\([^;\n]{0,90}?"((?:[^"\\]|\\.)*)"',text):
            try:label=json.loads('"'+match.group(1)+'"').strip()
            except ValueError:continue
            if 4<=len(label)<=110 and re.search('[А-Яа-яЁё]',label) and '\n' not in label and label not in {'Отмена','Применить','Назад','Готово','Удалить','Добавить'}:labels.add(label)
        values.extend({'activity':activity,'text':label} for label in sorted(labels))
    return json.dumps(values,ensure_ascii=False,indent=2)+'\n'
if __name__=='__main__':
    import sys
    path=ROOT/'app/src/main/assets/settings-search.json';value=build()
    if '--check' in sys.argv:
        if path.read_text()!=value:raise SystemExit('Settings search index is stale; run tools/generate_settings_search.py')
    else:path.write_text(value)
    print('Settings search index:',len(json.loads(value)),'controls')
