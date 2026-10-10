"""Execute production shell composition and shared DIM ownership; never touch vehicle paths."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HudDimRegressionTest(unittest.TestCase):
    def test_failed_command_report_is_saved_before_failure_is_presented(self):
        source=(ROOT/'app/src/main/java/dezz/status/widget/HudLcaPatchActivity.java').read_text().split('private void execute(',1)[1]
        self.assertLess(source.index('BackupFiles.atomicWrite(record'),source.index('if(!result.success()||result.truncated)'))
        self.assertIn('report_persist_failed',source)

    def test_block_is_exact_without_notrunc_and_failures_preserve_input(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            harness = root / 'Block.java'
            harness.write_text('''
import dezz.status.widget.adb.HudLcaPatch;
public class Block {
 public static void main(String[] a)throws Exception {
  var method=HudLcaPatch.class.getDeclaredMethod("block",String.class,HudLcaPatch.Mode.class);
  method.setAccessible(true);
  System.out.print(method.invoke(null,"$d/input.so",HudLcaPatch.Mode.valueOf(a[0])));
 }
}''')
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', folder,
                            str(ROOT/'app/src/main/java/dezz/status/widget/adb/HudLcaPatch.java'),
                            str(harness)], check=True, capture_output=True)
            original = bytes(range(256))*1048 + bytes(range(136))
            self.assertEqual(len(original), 268424)
            target = root/'input.so'
            for mode, expected in {
                'ORIGINAL': '820000d0830000d0423c289163801991e0070032e1031faae4031a2a2fd4ff97',
                'SIMPLE': '5f070071e80700321a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5',
                'GUIDE': '5f070071e8031f2a1a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5',
                'AR': '5f070071e8031f321a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5',
            }.items():
                target.write_bytes(original)
                block = subprocess.check_output(['java','-cp',folder,'Block',mode],text=True)
                self.assertNotIn('conv=',block)
                script = 'set -e\nd="'+folder+'"\n'+block
                run = subprocess.run(['sh','-c',script],text=True,capture_output=True)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)
                self.assertEqual(target.read_bytes(),original[:124248]+bytes.fromhex(expected)+original[124280:])
            fake = root/'bin'; fake.mkdir()
            dd = fake/'dd'
            dd.write_text('#!/bin/sh\necho "dd: No space left on device" >&2\nexit 7\n')
            dd.chmod(0o755)
            env=dict(os.environ,PATH=str(fake)+os.pathsep+os.environ['PATH'])
            target.write_bytes(original)
            run=subprocess.run(['sh','-c',script],env=env,text=True,capture_output=True)
            self.assertEqual(run.returncode,7)
            self.assertIn('block_error=no_space rc=7',run.stdout)
            self.assertEqual(target.read_bytes(),original)
            # Failure after the prefix was already written must also leave input untouched.
            import shutil
            real_dd=shutil.which('dd')
            dd.write_text('#!/bin/sh\ncase "$*" in *skip=*) echo "dd: Permission denied" >&2; exit 9;; esac\nexec "'+real_dd+'" "$@"\n')
            run=subprocess.run(['sh','-c',script],env=env,text=True,capture_output=True)
            self.assertEqual(run.returncode,9)
            self.assertIn('stage=block_suffix',run.stdout)
            self.assertIn('block_error=permission rc=9',run.stdout)
            self.assertEqual(target.read_bytes(),original)
            # Even exit zero with a short read fails the size guard before replacement.
            dd.write_text('#!/bin/sh\nexit 0\n')
            run=subprocess.run(['sh','-c',script],env=env,text=True,capture_output=True)
            self.assertNotEqual(run.returncode,0)
            self.assertIn('stage=block_size',run.stdout)
            self.assertEqual(target.read_bytes(),original)

    def test_shared_dim_owner_survives_reads_failures_and_concurrency(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            sources={
                'android/content/Context.java': '''package android.content; public class Context {
 public Context app; public Context getApplicationContext(){return app==null?this:app;} }''',
                'android/os/SystemClock.java': '''package android.os; public class SystemClock {
 public static long now=1; public static long elapsedRealtime(){return now;} }''',
                'dezz/status/widget/diagnostics/DiagnosticJournal.java': '''package dezz.status.widget.diagnostics;
 public class DiagnosticJournal { public static void infoAsync(String a,String b){} public static void warn(String a,String b){} }''',
                'com/ecarx/xui/adaptapi/diminteraction/DimInteraction.java': '''package com.ecarx.xui.adaptapi.diminteraction;
 import android.content.Context;
 public class DimInteraction {
 public static int creates; public static Context context; public static boolean failCreate,failMenu;
 public static final Object MENU=new Object();
 public static DimInteraction create(Context c){creates++;context=c;if(failCreate)throw new IllegalStateException();return new DimInteraction();}
 public Object getDimMenuInteraction(){if(failMenu)throw new IllegalStateException();return MENU;}
 }''',
                'Replay.java': '''import android.content.Context;
 import com.ecarx.xui.adaptapi.diminteraction.DimInteraction;
 import dezz.status.widget.dim.DimInteractionAccess;
 public class Replay {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
 Context app=new Context(),activity=new Context();activity.app=app;
 DimInteraction.failCreate=true;
 try{DimInteractionAccess.menu(activity);throw new AssertionError();}catch(Exception expected){}
 for(int i=0;i<1000;i++)try{DimInteractionAccess.menu(activity);}catch(Exception expected){}
 check(DimInteraction.creates==1);
 android.os.SystemClock.now=60001;DimInteraction.failCreate=false;
 Object owner=DimInteractionAccess.menu(activity);check(DimInteraction.context==app);
 DimInteraction.failMenu=true;
 for(int i=0;i<1000;i++)try{DimInteractionAccess.menu(activity);}catch(Exception expected){}
 check(DimInteraction.creates==2);DimInteraction.failMenu=false;
 java.util.List<Thread> threads=new java.util.ArrayList<>();
 java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger();
 for(int t=0;t<8;t++){Thread thread=new Thread(()->{
  for(int i=0;i<2000;i++)try{check(DimInteractionAccess.menu(activity)==owner);}catch(Throwable e){failures.incrementAndGet();}
 });threads.add(thread);thread.start();}
 for(Thread t:threads)t.join();check(failures.get()==0);check(DimInteraction.creates==2);
 }
 }'''}
            paths=[]
            for name,content in sources.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);paths.append(str(p))
            paths.append(str(ROOT/'app/src/main/java/dezz/status/widget/dim/DimInteractionAccess.java'))
            subprocess.run(['java','com.sun.tools.javac.Main','-d',folder,*paths],check=True,capture_output=True)
            subprocess.run(['java','-cp',folder,'Replay'],check=True,capture_output=True)
            # Every production DIM factory use must pass through the shared owner.
            for relative in ('instrument/InstrumentDisplayLauncher.java','media/ButtonDriverAppLauncher.java','dim/DimMenuVendorBridge.java'):
                text=(ROOT/'app/src/main/java/dezz/status/widget'/relative).read_text()
                self.assertIn('DimInteractionAccess.menu(',text)
                self.assertNotIn('diminteraction.DimInteraction"',text)


if __name__ == '__main__':
    unittest.main()
