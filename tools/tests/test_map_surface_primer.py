"""Production EGL primer against fault-injectable API doubles; not GPU verification."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import ROOT

SOURCES = {
 'android/view/Surface.java': 'package android.view; public class Surface {public boolean valid=true; public boolean isValid(){return valid;}}',
 'dezz/status/widget/diagnostics/DiagnosticJournal.java': 'package dezz.status.widget.diagnostics; public class DiagnosticJournal {public static void infoAsync(String t,String s){}}',
 'android/opengl/GLES20.java': '''package android.opengl;
public class GLES20 {
 public static final int GL_COLOR_BUFFER_BIT=1,GL_NO_ERROR=0;
 public static void glClearColor(float r,float g,float b,float a){if(r!=0||g!=0||b!=0||a!=0)throw new AssertionError("not transparent");}
 public static void glClear(int mask){if(mask!=GL_COLOR_BUFFER_BIT)throw new AssertionError();}
 public static int glGetError(){return EGL14.fail.equals("clear")?1:0;}
}''',
 'android/opengl/EGL14.java': '''package android.opengl;
import android.view.Surface;
public class EGL14 {
 public static final int EGL_DEFAULT_DISPLAY=0,EGL_RED_SIZE=1,EGL_GREEN_SIZE=2,EGL_BLUE_SIZE=3,EGL_ALPHA_SIZE=4,EGL_RENDERABLE_TYPE=5,EGL_OPENGL_ES2_BIT=6,EGL_SURFACE_TYPE=7,EGL_WINDOW_BIT=8,EGL_NONE=9,EGL_CONTEXT_CLIENT_VERSION=10;
 public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();
 public static final EGLContext EGL_NO_CONTEXT=new EGLContext();
 public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
 public static String fail=""; public static int contexts,windows,swaps,active;
 public static EGLDisplay eglGetDisplay(int d){return new EGLDisplay();}
 public static boolean eglInitialize(EGLDisplay d,int[] a,int o,int[] b,int p){return !fail.equals("initialize");}
 public static boolean eglChooseConfig(EGLDisplay d,int[] a,int o,EGLConfig[] c,int p,int n,int[] count,int q){c[0]=new EGLConfig();count[0]=1;return !fail.equals("config");}
 public static EGLContext eglCreateContext(EGLDisplay d,EGLConfig c,EGLContext s,int[] a,int o){if(fail.equals("context"))return EGL_NO_CONTEXT;contexts++;return new EGLContext();}
 public static EGLSurface eglCreateWindowSurface(EGLDisplay d,EGLConfig c,Object s,int[] a,int o){if(fail.equals("surface"))return EGL_NO_SURFACE;windows++;return new EGLSurface();}
 public static boolean eglMakeCurrent(EGLDisplay d,EGLSurface draw,EGLSurface read,EGLContext c){if(c==EGL_NO_CONTEXT){active=0;return true;}if(fail.equals("current"))return false;active++;return true;}
 public static boolean eglSwapBuffers(EGLDisplay d,EGLSurface s){if(fail.equals("publish"))return false;swaps++;return true;}
 public static boolean eglDestroySurface(EGLDisplay d,EGLSurface s){windows--;return true;}
 public static boolean eglDestroyContext(EGLDisplay d,EGLContext s){contexts--;return true;}
 public static boolean eglReleaseThread(){return true;}
}''',
 'dezz/status/widget/navigation/PrimerReplay.java': '''package dezz.status.widget.navigation;
import android.opengl.EGL14;
import android.view.Surface;
import java.util.concurrent.*;
public class PrimerReplay {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  EGL14.fail=args[0];Surface surface=new Surface();
  if(args[0].equals("invalid"))surface.valid=false;
  MapSurfacePrimer primer=new MapSurfacePrimer(surface);
  CountDownLatch done=new CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
  Runnable finished=()->{try{check(primer.complete,"completion flag before dispatch");check(EGL14.contexts==0&&EGL14.windows==0&&EGL14.active==0,"producer disconnected before dispatch");calls.incrementAndGet();}catch(Throwable t){failure.set(t);}finally{done.countDown();}};
  primer.start(finished);primer.start(finished);
  check(done.await(3,TimeUnit.SECONDS),"failure must still complete");
  if(failure.get()!=null)throw new AssertionError(failure.get());
  primer.start(finished);check(calls.get()==1,"same surface only once");
  check(EGL14.swaps==(args[0].equals("ok")?1:0),"only complete clear publishes");
  if(args[0].equals("ok")){
   MapSurfacePrimer second=new MapSurfacePrimer(new Surface());CountDownLatch secondDone=new CountDownLatch(1);
   second.start(secondDone::countDown);check(secondDone.await(3,TimeUnit.SECONDS),"second display independent");
   check(EGL14.swaps==2,"one initial buffer per new surface");
  }
 }
}''',
}
for name in ('EGLConfig', 'EGLContext', 'EGLDisplay', 'EGLSurface'):
    SOURCES['android/opengl/' + name + '.java'] = 'package android.opengl; public class ' + name + ' {}'


class MapSurfacePrimerTest(unittest.TestCase):
    def test_initial_clear_completion_and_all_native_failure_stages(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            files = []
            for name, source in SOURCES.items():
                target = folder / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source)
                files.append(str(target))
            files.append(str(ROOT / 'app/src/main/java/dezz/status/widget/navigation/MapSurfacePrimer.java'))
            result = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', temp, *files], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            for stage in ('ok', 'invalid', 'initialize', 'config', 'context', 'surface', 'current', 'clear', 'publish'):
                with self.subTest(stage=stage):
                    result = subprocess.run(['java', '-cp', temp, 'dezz.status.widget.navigation.PrimerReplay', stage], capture_output=True, text=True, timeout=8)
                    self.assertEqual(result.returncode, 0, result.stderr)
