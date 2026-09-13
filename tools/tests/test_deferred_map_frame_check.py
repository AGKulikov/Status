"""Replay the Android draw/readback contract with production gate and scheduling code.

The TextureView fixture rejects a read during draw as the Android API requires callers
to avoid it. This verifies scheduling and lifecycle; it is not a KX11 GPU emulator.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'android/graphics/Bitmap.java': '''package android.graphics;
      public class Bitmap {
        public final int[] pixels; private boolean recycled;
        public Bitmap(int[] data){pixels=data;}
        public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;}
        public int getWidth(){return 32;} public int getHeight(){return 18;}
        public void getPixels(int[] dst,int offset,int stride,int x,int y,int w,int h){
          System.arraycopy(pixels,0,dst,offset,pixels.length);}
      }''',
    'android/view/TextureView.java': '''package android.view;
      public class TextureView {
        public boolean drawing, available=true, copyFails; public int reads;
        public int[] pixels=new int[32*18]; public Runnable updateDuringCopy;
        public boolean isAvailable(){return available;}
        public android.graphics.Bitmap getBitmap(int width,int height){
          reads++;
          if(drawing || copyFails)throw new IllegalStateException("Readback during draw or copy failure");
          if(updateDuringCopy!=null)updateDuringCopy.run();
          return new android.graphics.Bitmap(pixels.clone());
        }
      }''',
    'dezz/status/widget/navigation/DeferredFrameReplay.java': r'''package dezz.status.widget.navigation;
      import android.view.TextureView;
      import java.util.*;
      public class DeferredFrameReplay {
        static void check(boolean value){if(!value)throw new AssertionError();}
        static class Queue implements MapFirstFrameDetector.DeferredCheck.Queue {
          final ArrayList<Runnable> tasks=new ArrayList<>(); boolean reject;
          public boolean post(Runnable task){if(reject)return false;tasks.add(task);return true;}
          public void remove(Runnable task){tasks.remove(task);}
          void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}
        }
        static class Owner {
          final Queue queue=new Queue(); final TextureView texture=new TextureView();
          final MapFirstFrameDetector.Gate gate=new MapFirstFrameDetector.Gate();
          final MapFirstFrameDetector.DeferredCheck deferred=
              new MapFirstFrameDetector.DeferredCheck(queue,this::read);
          boolean ready=true, shown; int checks;
          Owner(){content();}
          void content(){Arrays.fill(texture.pixels,0);for(int i=0;i<texture.pixels.length;i+=17)
            texture.pixels[i]=0xff55b830;}
          void read(){check(!texture.drawing);checks++;shown=gate.accept(ready,texture);}
          void draw(){texture.drawing=true;deferred.onFrame();texture.drawing=false;}
          void frame(){draw();queue.drain();}
          void replace(){deferred.cancel();gate.reset();shown=false;}
        }
        static void inlineReadFailsPostedReadQualifiesThreeFrames(){
          Owner old=new Owner();
          for(int i=0;i<3;i++){
            old.texture.drawing=true;
            check(!old.gate.accept(true,old.texture));
            check(old.gate.diagnosticState().contains("COPY_FAILED"));
            old.texture.drawing=false;
          }
          Owner current=new Owner();current.draw();
          check(current.texture.reads==0 && !current.shown);
          current.queue.drain();check(!current.shown);
          current.frame();check(!current.shown);
          current.frame();check(current.shown && current.texture.reads==3);
        }
        static void coalescedCallbacksDoNotInventAdditionalBuffers(){
          Owner owner=new Owner();
          owner.draw();owner.draw();owner.draw();
          check(owner.queue.tasks.size()==1 && owner.texture.reads==0);
          owner.queue.drain();check(owner.checks==1 && !owner.shown);
          owner.queue.drain();check(owner.checks==1 && !owner.shown);
          owner.frame();check(!owner.shown);owner.frame();check(owner.shown);
        }
        static void copyCallbackDoesNotScheduleAReadbackLoop(){
          Owner owner=new Owner();owner.texture.updateDuringCopy=owner.deferred::onFrame;
          owner.frame();check(owner.checks==1 && owner.queue.tasks.isEmpty());
          owner.frame();owner.frame();check(owner.checks==3 && owner.shown);
        }
        static void staleQueuedTaskCannotReadOrEraseReplacement(){
          Owner owner=new Owner();owner.draw();Runnable stale=owner.queue.tasks.get(0);
          owner.replace();owner.draw();check(owner.queue.tasks.size()==1);
          stale.run();check(owner.texture.reads==0 && owner.queue.tasks.size()==1);
          owner.queue.drain();check(owner.texture.reads==1 && !owner.shown);
          owner.frame();check(!owner.shown);owner.frame();check(owner.shown);
          owner.draw();Runnable detached=owner.queue.tasks.get(0);owner.replace();
          detached.run();check(owner.texture.reads==3 && owner.queue.tasks.isEmpty());
        }
        static void ackAndInvalidPixelsStillKeepBothMapsHidden(){
          for(int color:new int[]{0,0xffffffff,0xff000000,0xff888888}){
            Owner owner=new Owner();Arrays.fill(owner.texture.pixels,color);
            owner.frame();owner.frame();owner.frame();check(!owner.shown);
            check(owner.gate.diagnosticState().contains("REJECTED_PIXELS"));
          }
          Owner owner=new Owner();owner.ready=false;
          owner.frame();owner.frame();owner.frame();check(!owner.shown && owner.texture.reads==0);
          check(owner.gate.diagnosticState().contains("awaiting-producer"));
          owner.ready=true;owner.frame();owner.frame();check(!owner.shown);
          owner.texture.copyFails=true;owner.frame();check(!owner.shown);
          check(owner.gate.diagnosticState().contains("COPY_FAILED"));
          owner.texture.copyFails=false;
          owner.frame();owner.frame();check(!owner.shown);owner.frame();check(owner.shown);
          owner.ready=false;owner.frame();check(!owner.shown);
          owner.ready=true;owner.frame();owner.frame();check(!owner.shown);owner.frame();check(owner.shown);
        }
        static void independentOwnersAndRejectedPosts(){
          Owner hud=new Owner(), cluster=new Owner();
          hud.frame();hud.frame();hud.frame();check(hud.shown && !cluster.shown);
          cluster.queue.reject=true;cluster.draw();check(cluster.queue.tasks.isEmpty());
          cluster.queue.reject=false;cluster.frame();check(!cluster.shown);
          hud.replace();check(!hud.shown);
          cluster.frame();cluster.frame();check(cluster.shown && !hud.shown);
          hud.texture.available=false;hud.frame();check(!hud.shown && hud.texture.reads==3);
          check(hud.gate.diagnosticState().contains("NO_SURFACE"));
        }
        public static void main(String[] args)throws Exception{
          DeferredFrameReplay.class.getDeclaredMethod(args[0]).invoke(null);
        }
      }''',
}


class DeferredMapFrameCheckTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='natro-deferred-frame-')
        cls.folder = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            path = cls.folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        files.append(str(ROOT / 'app/src/main/java/dezz/status/widget/navigation/MapFirstFrameDetector.java'))
        compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
        result = subprocess.run([*compiler, '-d', str(cls.folder), *files], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(['java', '-cp', str(self.folder),
                        'dezz.status.widget.navigation.DeferredFrameReplay', name], check=True)


for case in ('inlineReadFailsPostedReadQualifiesThreeFrames',
             'coalescedCallbacksDoNotInventAdditionalBuffers',
             'copyCallbackDoesNotScheduleAReadbackLoop',
             'staleQueuedTaskCannotReadOrEraseReplacement',
             'ackAndInvalidPixelsStillKeepBothMapsHidden',
             'independentOwnersAndRejectedPosts'):
    setattr(DeferredMapFrameCheckTests, 'test_' + case, lambda self, name=case: self.replay(name))
