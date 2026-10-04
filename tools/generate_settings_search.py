#!/usr/bin/env python3
"""Build a deterministic field index with allowlisted routes to actual nested editor forms."""
import json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def method_ranges(source):
    # Mask comments/strings while keeping source offsets and line starts. Balanced braces then
    # retain complete form bodies, including their listeners, rather than treating onClick as owner.
    masked=re.sub(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda m: ''.join('\n' if c=='\n' else ' ' for c in m[0]),source)
    result=[]
    for method in re.finditer(r'^    (?:public|private|protected)\s+(?:(?:static|final|synchronized)\s+)*[\w<>.?\[\], @]+?\s+(\w+)\s*\([^;{}]*\)\s*(?:throws[^{}]+)?\{',masked,re.M):
        depth=1;end=method.end()
        while end<len(masked) and depth:
            depth+=(masked[end]=='{')-(masked[end]=='}');end+=1
        result.append((method.start(),end,method[1]))
    return result

def routes(source):
    result={}
    annotation=r'@dezz\.status\.widget\.settings\.SettingsSearchForm(?:\(([^\n]*)\))?\s*(?:@\w+\s*)?(?:private|public|protected)\s+[\w<>]+\s+(\w+)'
    for match in re.finditer(annotation,source):
        alias=re.search(r'value\s*=\s*"([^"\\]+)"',match[1] or '')
        name=alias[1] if alias else match[2]
        result[name]=name
    return result

def build():
    values=[]
    for path in sorted((ROOT/'app/src/main/java/dezz/status/widget').rglob('*.java')):
        if 'SettingsActivity' not in path.name and path.name not in {'MainActivity.java','PresetsActivity.java','PhoneAppIconsActivity.java'}:continue
        if '/servicemode/' in str(path) or '/drivemode/' in str(path):continue
        text=path.read_text();package=re.search(r'package\s+([\w.]+);',text)
        if not package:continue
        activity=package.group(1)+'.'+path.stem
        labels=set();methods=method_ranges(text);allowed=routes(text)
        for match in re.finditer(r'\b(?:setText|setHint|section|heading|label|title|button|toggle|number|slider|\w*Slider|color\w*|switch\w*|stringField|add\w*|labeled\w*)\s*\([^;{}]{0,160}?"((?:[^"\\]|\\.)*)"',text):
            try:label=json.loads('"'+match.group(1)+'"').strip()
            except ValueError:continue
            if 4<=len(label)<=110 and re.search('[А-Яа-яЁё]',label) and '\n' not in label and label not in {'Отмена','Применить','Назад','Готово','Удалить','Добавить'}:
                owner=next((name for start,end,name in methods if start<=match.start()<end),'')
                route=allowed.get(owner,'')
                if not owner:
                    # Nested view builders belong to an existing object editor, not to the list page.
                    fallback={'ScenarioSettingsActivity':'showEditor','IntentScenarioSettingsActivity':'showEditor',
                              'InstrumentPanelSettingsActivity':'editSelected','HudPanelSettingsActivity':'editGlobalOptions'}
                    route=allowed.get(fallback.get(path.stem,''),'')
                labels.add((label,route))
        values.extend({'activity':activity,'text':label,'route':route} for label,route in sorted(labels))
    inherited={'PassengerLauncherSettingsActivity':'LauncherSettingsActivity','PassengerPanelSettingsActivity':'DriverPanelSettingsActivity',
               'PassengerFavoritesSettingsActivity':'DriverFavoritesSettingsActivity','PassengerAllAppsSettingsActivity':'AllAppsSettingsActivity'}
    for child,parent in inherited.items():
        values.extend(dict(value,activity='dezz.status.widget.'+child) for value in list(values)
                      if value['activity']=='dezz.status.widget.'+parent)
    return json.dumps(values,ensure_ascii=False,indent=2)+'\n'
if __name__=='__main__':
    import sys
    path=ROOT/'app/src/main/assets/settings-search.json';value=build()
    if '--check' in sys.argv:
        if path.read_text()!=value:raise SystemExit('Settings search index is stale; run tools/generate_settings_search.py')
    else:path.write_text(value)
    print('Settings search index:',len(json.loads(value)),'controls')
