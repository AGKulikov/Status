#!/usr/bin/env python3
"""Compare private Baksmali output with sequential labels; publish only aggregate evidence."""
import argparse, json, re
from pathlib import Path
HOOKS = {
 'com/yandex/alice/audio/d.smali': 'invoke-static {}, Lru/natro/navigation/NativeAliceBridge;->beforeAudioFocus()V',
 'com/yandex/alice/io/listening/d.smali': 'invoke-static/range {p1 .. p1}, Lru/natro/navigation/NativeAliceBridge;->onEngineError(Ljava/lang/Object;)V',
 'com/yandex/quark/audio/internal/d.smali': 'invoke-static {}, Lru/natro/navigation/NativeAliceBridge;->beforeAudioFocus()V',
}
def normalize(text):
 lines=[]
 for line in text.splitlines():
  value=line.strip()
  if not value or value=='nop': continue # DEX switch-payload alignment padding
  if value.startswith('.field '):
   value=re.sub(r'(:Z) = false$',r'\1',value)
   value=re.sub(r'(:I) = 0x0$',r'\1',value)
   value=re.sub(r'(:[L\[].*) = null$',r'\1',value)
  lines.append(value)
 return lines

def compare(before, after):
 names={p.relative_to(before).as_posix() for p in before.rglob('*.smali')}
 if names != {p.relative_to(after).as_posix() for p in after.rglob('*.smali')}: raise ValueError('Class set changed')
 hooks=[];canonical=[]
 for name in sorted(names):
  old=(before/name).read_text();new=(after/name).read_text()
  if name in HOOKS:
   needle=HOOKS[name]
   if new.count(needle)!=1 or needle in old: raise ValueError('Wrong hook count '+name)
   new=new.replace(needle,'');hooks.append(name)
  if normalize(old)!=normalize(new): raise ValueError('Unexpected semantic diff '+name)
  if old != new: canonical.append(name)
 return dict(classes=len(names),expectedHookClasses=hooks,allOtherCodeAndDeclarationsEquivalent=True,
             canonicalizedDeclarationsOrAlignment=canonical,
             normalization='Blank lines, NOP alignment, explicit default zero/null static values only')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('before',type=Path);p.add_argument('after',type=Path)
 a=p.parse_args();print(json.dumps(compare(a.before,a.after),indent=2))
