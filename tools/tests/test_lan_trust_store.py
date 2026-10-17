"""Production trust adapter against an in-memory Android prefs double, not a reboot test."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]

class LanTrustStoreTest(unittest.TestCase):
    def test_durable_grants_revoke_and_failed_commit(self):
        sources={
            'android/content/Context.java':'''package android.content;
public class Context {public static final int MODE_PRIVATE=0; public Context getApplicationContext(){return this;}
public final SharedPreferences prefs=new SharedPreferences();}''',
            'android/content/SharedPreferences.java':'''package android.content;
import java.util.*;
public class SharedPreferences {
 public boolean fail; private final Map<String,Object> data=new HashMap<>();
 public Set<String> getStringSet(String key,Set<String> fallback){return (Set<String>)data.getOrDefault(key,fallback);}
 public boolean getBoolean(String key,boolean fallback){return (Boolean)data.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public class Editor {private final Map<String,Object> next=new HashMap<>();
  public Editor putStringSet(String key,Set<String> value){next.put(key,new HashSet<>(value));return this;}
  public Editor putBoolean(String key,boolean value){next.put(key,value);return this;}
  public boolean commit(){if(fail)return false;data.putAll(next);return true;}}
}''',
            'dezz/status/widget/backup/BackupPreferences.java':'''package dezz.status.widget.backup;
import android.content.*;
public class BackupPreferences {public static SharedPreferences open(Context c,String name,int mode){return c.prefs;}}''',
            'Replay.java':'''import android.content.Context;import dezz.status.widget.transfer.*;
public class Replay {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[]args){
  Context context=new Context();LanTrustStore first=new LanTrustStore(context);
  ok(!first.autoStart()&&!first.commandsAllowed());
  LanPairing pair=new LanPairing(0,first);String token=pair.pair(pair.pin(),1,true);
  ok(token!=null&&first.autoStart());ok(first.setCommandsAllowed(true));
  LanTrustStore restarted=new LanTrustStore(context);ok(restarted.autoStart()&&restarted.commandsAllowed());
  ok(new LanPairing(0,restarted).authorized(token,99999999));
  ok(restarted.setAutoStart(false));ok(!first.autoStart()&&first.commandsAllowed());
  ok(pair.forget(token));ok(!first.autoStart()&&!first.commandsAllowed());
  token=pair.pair(pair.pin(),2,true);ok(token!=null);ok(first.setCommandsAllowed(true));
  context.prefs.fail=true;ok(!pair.rotate(3));ok(!first.commandsAllowed()&&!first.autoStart());
  ok(!pair.authorized(token,4));System.out.println("PASS");
 }
}'''}
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory)
            for name,source in sources.items():
                path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
            base=ROOT/'app/src/main/java/dezz/status/widget/transfer'
            result=subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,work.rglob('*.java')),str(base/'LanPairing.java'),str(base/'LanTrustStore.java')],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',directory,'Replay'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)

if __name__=='__main__':unittest.main()
