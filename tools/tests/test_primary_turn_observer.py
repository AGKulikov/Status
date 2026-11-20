"""Exercise the production PrimaryTurnObserver across asynchronous map captures."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MOD = ROOT / 'navigator-mod/src/main/java/ru/natro/navigation'
JAVA = {}

def fixture(path, body):
    package, _, name = path.rpartition('.')
    JAVA[path.replace('.', '/') + '.java'] = 'package ' + package + ';\n' + body

fixture('android.os.Looper', 'public class Looper { public static Looper getMainLooper(){return new Looper();} }')
fixture('android.os.SystemClock', 'public class SystemClock { public static volatile long now=10000; public static long elapsedRealtime(){return now;} }')
fixture('android.os.Handler', '''
import java.util.*;
public class Handler {
    public Handler(Looper l){}
    private static class Job {
        final Runnable task; final long due;
        Job(Runnable r,long d){task=r; due=d;}
    }
    private static final ArrayList<Job> queue = new ArrayList<>();
    public boolean post(Runnable r){return postDelayed(r,0);}
    public boolean postDelayed(Runnable r,long d){synchronized(queue){queue.add(new Job(r,SystemClock.now+d));}return true;}
    public void removeCallbacks(Runnable r){synchronized(queue){queue.removeIf(j->j.task==r);}}
    public static void drain(){
        while(true){
            Job ready=null;
            synchronized(queue){
                for(Job j:queue)if(j.due<=SystemClock.now){ready=j;break;}
                if(ready==null)return;
                queue.remove(ready);
            }
            ready.task.run();
        }
    }
}''')
fixture('android.graphics.PointF','public class PointF {public float x,y; public PointF(float x,float y){this.x=x;this.y=y;}}')
fixture('android.graphics.Rect', '''
public class Rect {
    public int left,top,right,bottom;
    public Rect(){}
    public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    public Rect(Rect r){set(r);}
    public void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}
    public int width(){return right-left;} public int height(){return bottom-top;}
    public boolean contains(int x,int y){return x>=left&&y>=top&&x<right&&y<bottom;}
    public boolean contains(Rect r){return !isEmpty()&&r.left>=left&&r.top>=top&&r.right<=right&&r.bottom<=bottom;}
    public boolean isEmpty(){return left>=right||top>=bottom;}
    public void inset(int x,int y){left+=x;right-=x;top+=y;bottom-=y;}
    public boolean intersect(Rect r){left=Math.max(left,r.left);top=Math.max(top,r.top);right=Math.min(right,r.right);bottom=Math.min(bottom,r.bottom);return !isEmpty();}
    public void union(Rect r){
        if(r.isEmpty())return;
        if(isEmpty()){set(r);return;}
        left=Math.min(left,r.left);top=Math.min(top,r.top);right=Math.max(right,r.right);bottom=Math.max(bottom,r.bottom);
    }
}''')
fixture('android.graphics.Bitmap', '''
public class Bitmap {
    public enum Config {ARGB_8888}
    public final int w,h;
    public final int[] pixels;
    public static volatile java.util.concurrent.CountDownLatch entered, resume;
    private boolean recycled;
    public Bitmap(int w,int h){this.w=w;this.h=h;pixels=new int[w*h];}
    public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(w,h);}
    public int getWidth(){return w;} public int getHeight(){return h;}
    public boolean isRecycled(){return recycled;}
    public void recycle(){if(recycled)throw new AssertionError("recycled twice");recycled=true;}
    public void getPixels(int[] o,int off,int stride,int x,int y,int w,int h){
        if(recycled)throw new IllegalStateException("recycled");
        if(!Thread.currentThread().getName().equals("NatroTurnArtwork"))throw new AssertionError("raster extraction on UI thread");
        if(entered!=null){
            entered.countDown();
            try{if(!resume.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("blocked worker");}
            catch(InterruptedException e){throw new AssertionError(e);}
        }
        for(int j=0;j<h;j++)System.arraycopy(pixels,(y+j)*this.w+x,o,off+j*stride,w);
    }
}''')
fixture('android.graphics.Matrix','public class Matrix { public boolean isIdentity(){return true;} }')
fixture('android.graphics.SurfaceTexture','public class SurfaceTexture {public android.graphics.Bitmap buffer;}')
fixture('android.content.Context','public class Context {}')
fixture('android.content.ContextWrapper','public class ContextWrapper extends Context {public Context getBaseContext(){return new Context();}}')
fixture('android.view.WindowManager','public class WindowManager {public static class LayoutParams {public static final int FLAG_SECURE=8192;public int flags;}}')
fixture('android.view.Window','public class Window {public final WindowManager.LayoutParams lp=new WindowManager.LayoutParams();public WindowManager.LayoutParams getAttributes(){return lp;}}')
fixture('android.app.Activity','public class Activity extends android.content.ContextWrapper {public final android.view.Window w=new android.view.Window();public android.view.Window getWindow(){return w;}}')
fixture('android.view.Surface','''
import android.graphics.*;
public class Surface {
    public final Bitmap buffer;
    public Surface(Bitmap b){buffer=b;} public Surface(SurfaceTexture t){buffer=t.buffer;}
    public boolean isValid(){return true;} public void release(){}
}''')
fixture('android.view.View','''
public class View {
    public boolean shown=true,attached=true; public int width=240,height=100;
    public final android.app.Activity activity=new android.app.Activity();
    public android.content.Context getContext(){return activity;}
    public boolean isShown(){return shown;} public boolean isAttachedToWindow(){return attached;}
    public int getWidth(){return width;} public int getHeight(){return height;}
}''')
fixture('android.view.ViewGroup','''
import java.util.*;
public class ViewGroup extends View {public final List<View> children=new ArrayList<>();
    public int getChildCount(){return children.size();} public View getChildAt(int n){return children.get(n);}
}''')
fixture('android.view.SurfaceHolder','''
public interface SurfaceHolder {
    interface Callback {
        void surfaceCreated(SurfaceHolder holder);
        void surfaceChanged(SurfaceHolder holder,int format,int w,int h);
        void surfaceDestroyed(SurfaceHolder holder);
    }
    android.graphics.Rect getSurfaceFrame(); Surface getSurface();
    void addCallback(Callback c); void removeCallback(Callback c);
}''')
fixture('android.view.SurfaceView','''
import android.graphics.*;
public class SurfaceView extends View implements SurfaceHolder {
    public final Surface surface;
    public final java.util.List<Callback> callbacks=new java.util.ArrayList<>();
    public SurfaceView(Bitmap b){width=b.w;height=b.h;surface=new Surface(b);}
    public SurfaceHolder getHolder(){return this;} public Surface getSurface(){return surface;}
    public Rect getSurfaceFrame(){return new Rect(0,0,width,height);}
    public void addCallback(Callback c){callbacks.add(c);}
    public void removeCallback(Callback c){callbacks.remove(c);}
    public void signalResize(){for(Callback c:new java.util.ArrayList<>(callbacks))c.surfaceChanged(this,0,width,height);}
    public void signalDestroy(){for(Callback c:new java.util.ArrayList<>(callbacks))c.surfaceDestroyed(this);}
}''')
fixture('android.view.TextureView','''
import android.graphics.*;
public class TextureView extends View {
    private SurfaceTexture surfaceTexture = new SurfaceTexture();
    public TextureView(Bitmap b){width=b.w;height=b.h;surfaceTexture.buffer=b;}
    public boolean isAvailable(){return true;} public SurfaceTexture getSurfaceTexture(){return surfaceTexture;}
    public Matrix getTransform(Matrix m){return new Matrix();}
    public void recreate(Bitmap b){surfaceTexture=new SurfaceTexture();surfaceTexture.buffer=b;}
}''')
fixture('com.yandex.runtime.view.PlatformGLSurfaceView','public class PlatformGLSurfaceView extends android.view.SurfaceView {public PlatformGLSurfaceView(android.graphics.Bitmap b){super(b);}}')
fixture('com.yandex.runtime.view.PlatformGLSurfaceViewBelowApi34Workaround','public class PlatformGLSurfaceViewBelowApi34Workaround extends PlatformGLSurfaceView {public PlatformGLSurfaceViewBelowApi34Workaround(android.graphics.Bitmap b){super(b);}}')
fixture('com.yandex.runtime.view.PlatformVulkanSurfaceView','public class PlatformVulkanSurfaceView extends android.view.SurfaceView {public PlatformVulkanSurfaceView(android.graphics.Bitmap b){super(b);}}')
fixture('com.yandex.runtime.view.PlatformGLTextureView','public class PlatformGLTextureView {public static class TextureViewImpl extends android.view.TextureView {public TextureViewImpl(android.graphics.Bitmap b){super(b);}}}')
fixture('android.view.PixelCopy','''
import android.graphics.*;
import android.os.Handler;
import java.util.*;
public class PixelCopy {
    public static final int SUCCESS=0;
    public static boolean failNext;
    public interface OnPixelCopyFinishedListener {void onPixelCopyFinished(int status);}
    public static final List<Request> requests=new ArrayList<>();
    public static class Request {
        public final Bitmap target;public final Rect crop;
        public boolean delivered;
        private final OnPixelCopyFinishedListener callback;
        Request(Bitmap b,Rect r,OnPixelCopyFinishedListener cb){target=b;crop=new Rect(r);callback=cb;}
        public void finish(int status){if(delivered)throw new AssertionError("callback repeated");delivered=true;callback.onPixelCopyFinished(status);}
    }
    public static void request(Surface source,Rect crop,Bitmap dest,OnPixelCopyFinishedListener cb,Handler h){
        if(failNext){failNext=false;throw new IllegalArgumentException("surface lost");}
        if(dest.w!=crop.width()||dest.h!=crop.height())throw new AssertionError("unscaled readback required");
        if((long)dest.w*dest.h>512*1024L)throw new AssertionError("readback budget");
        for(int y=0;y<dest.h;y++)
            System.arraycopy(source.buffer.pixels,(crop.top+y)*source.buffer.w+crop.left,dest.pixels,y*dest.w,dest.w);
        requests.add(new Request(dest,crop,cb));
    }
}''')
fixture('android.util.Log','public class Log {public static int d(String t,String s){return 0;}}')
fixture('com.yandex.mapkit.geometry.Point','public class Point {public final double latitude,longitude;public Point(double a,double b){latitude=a;longitude=b;}}')
fixture('ru.natro.navigation.NavigatorStatePublisher','''
final class NavigatorStatePublisher {
    static final class NavigationFrame {boolean routeActive=true,routeProgressValid;int routeSegmentIndex;double routeSegmentPosition;}
    static final class RouteTurnFrame {
        final String id,action; final double latitude,longitude,routeSegmentPosition; final float bearingDegrees; final int routeSegmentIndex;
        RouteTurnFrame(String id,String action,double lat,double lon,float bearing,int index,double fraction){
            this.id=id;this.action=action;latitude=lat;longitude=lon;bearingDegrees=bearing;routeSegmentIndex=index;routeSegmentPosition=fraction;
        }
        boolean hasContent(){return routeSegmentIndex>=0;}
    }
    static final class LaneGuidanceFrame {
        String id; double latitude,longitude,routeSegmentPosition; float bearingDegrees; int routeSegmentIndex;
        boolean hasMapPosition(){return true;}
    }
}''')
fixture('ru.natro.navigation.StockTurnTextures','''
import android.graphics.Bitmap;import java.util.*;
final class StockTurnTextures {
    static final class Texture {
        final Bitmap bitmap; final Object provider=new Object(); final float anchorX,anchorY;
        final boolean combined; final long revision; Object source=new Object();
        Texture(Bitmap b,long r){this(b,r,0,0,true);}
        Texture(Bitmap b,long r,float x,float y,boolean c){bitmap=b;revision=r;anchorX=x;anchorY=y;combined=c;}
    }
    static final IdentityHashMap<Object,List<Texture>> byWindow=new IdentityHashMap<>();
    static List<Texture> forWindow(Object key){return byWindow.getOrDefault(key,Collections.emptyList());}
}''')
fixture('ru.natro.navigation.PrimaryTurnReplay', '''
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import android.graphics.*;
import android.os.*;
import android.view.*;
import com.yandex.mapkit.geometry.Point;
import com.yandex.runtime.view.*;
import ru.natro.navigation.NavigatorStatePublisher.*;

public class PrimaryTurnReplay {
    public static final class Projected {
        final float x,y; Projected(float x,float y){this.x=x;this.y=y;}
        public float getX(){return x;}public float getY(){return y;}
    }
    public static final class Window {
        int w=240,h=100,dx=0,dy=0,alternateDx=0;
        public int width(){return w;} public int height(){return h;}
        public Projected worldToScreen(Point p){
            float x=(float)(p.longitude*8);
            return new Projected(x+dx+(x==32?alternateDx:0),(float)(p.latitude*8)+dy);
        }
    }
    public static final class MapView extends ViewGroup {final Window window; MapView(Window w){window=w;} public Window getMapWindow(){return window;}}
    final Window window=new Window(); final MapView view=new MapView(window);
    final NavigationFrame frame=new NavigationFrame();
    final AtomicInteger updates=new AtomicInteger();
    final PrimaryTurnObserver observer=new PrimaryTurnObserver(updates::incrementAndGet);
    long epoch=1;
    final RouteTurnFrame first=turn(10,32,24), second=turn(11,104,32);
    List<RouteTurnFrame> turns=Arrays.asList(first,second);
    final StockTurnTextures.Texture original=texture(1);
    Bitmap screen;

    PrimaryTurnReplay(String mode){
        if(mode.equals("budget")||mode.equals("far_unique")||mode.equals("large_budget")){window.w=1280;window.h=720;}
        view.width=window.w;view.height=window.h;screen=new Bitmap(window.w,window.h);
        if(mode.equals("textureView")||mode.equals("texture_buffer_swap")) view.children.add(new PlatformGLTextureView.TextureViewImpl(screen));
        else if(mode.equals("vulkan")) view.children.add(new PlatformVulkanSurfaceView(screen));
        else if(mode.equals("api28")) view.children.add(new PlatformGLSurfaceViewBelowApi34Workaround(screen));
        else if(mode.equals("foreign_surface")) view.children.add(new SurfaceView(screen));
        else if(mode.equals("nested_surface")){
            ViewGroup inner=new ViewGroup();inner.children.add(new PlatformGLSurfaceView(screen));view.children.add(inner);
        }else view.children.add(new PlatformGLSurfaceView(screen));
        select(original);
    }
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static RouteTurnFrame turn(int id,int x,int y){return new RouteTurnFrame("turn"+id,"LEFT",y/8d,x/8d,0,id,0);}
    static StockTurnTextures.Texture texture(int v){
        Bitmap b=new Bitmap(32,24);Arrays.fill(b.pixels,0xff234aca);
        for(int y=3;y<19;y++)for(int x=3;x<29;x++)
            if(x%6==1||y==7||(v>1 && x>18 && y>14)) b.pixels[y*32+x]=0xfff0f2ea;
        return new StockTurnTextures.Texture(b,v);
    }
    void select(StockTurnTextures.Texture t){StockTurnTextures.byWindow.put(window,Collections.singletonList(t));}
    void paint(RouteTurnFrame t,StockTurnTextures.Texture art){
        Projected p=window.worldToScreen(new Point(t.latitude,t.longitude));
        Bitmap b=art.bitmap;
        int x=Math.round(p.x-art.anchorX*b.w),top=Math.round(p.y-art.anchorY*b.h);
        for(int y=0;y<b.h;y++)System.arraycopy(b.pixels,y*b.w,screen.pixels,(top+y)*screen.w+x,b.w);
    }
    PrimaryTurnSample poll(){return observer.observe(view,window,epoch,turns,null,frame);}
    PixelCopy.Request last(){return PixelCopy.requests.get(PixelCopy.requests.size()-1);}
    void complete(int status)throws Exception {
        int before=updates.get();
        long end=System.nanoTime()+2_000_000_000L;
        while(updates.get()==before && System.nanoTime()<end){
            PixelCopy.Request request=last();
            if(!request.delivered)request.finish(status);
            Handler.drain();Thread.sleep(1);
        }
        check(updates.get()>before,"completion must refresh observer");
    }
    PrimaryTurnSample stored()throws Exception{
        java.lang.reflect.Field f=PrimaryTurnObserver.class.getDeclaredField("latest");f.setAccessible(true);
        return (PrimaryTurnSample)f.get(observer);
    }
    void finishInvalidated()throws Exception{
        java.lang.reflect.Field f=PrimaryTurnObserver.class.getDeclaredField("pending");f.setAccessible(true);
        if(f.getBoolean(observer))complete(0);
        else {PixelCopy.Request request=last();if(!request.delivered)request.finish(0);Handler.drain();}
    }
    void advance(long millis){SystemClock.now+=millis;Handler.drain();}
    void run(String which)throws Exception {
        paint(second,original);PrimaryTurnSample s;
        switch(which) {
        case "surface":
        case "vulkan":
        case "api28":
        case "textureView":
            check(poll()==null,"sample should require completed readback");
            check(last().crop.width()<window.w&&last().crop.height()<window.h,"small crop instead of full screen");
            for(int i=0;i<50;i++)poll();
            check(PixelCopy.requests.size()==1,"one capture in flight");
            complete(0);s=poll();check(s!=null&&s.turn==second&&s.texture==original,"right geo anchor and artwork");
            int count=PixelCopy.requests.size();
            for(int i=0;i<50;i++)poll();check(PixelCopy.requests.size()==count,"sample rate limited");
            advance(340);Arrays.fill(screen.pixels,0xff123456);poll();complete(0);check(poll()!=null,"one render gap retains confirmation");
            advance(340);poll();complete(0);check(poll()==null,"two verified absences clear marker");
            break;
        case "epoch":
            poll(); PixelCopy.Request obsolete=last();epoch=2;poll();
            check(PixelCopy.requests.size()==2,"new route gets own request");
            obsolete.finish(0);check(obsolete.target.isRecycled(),"discarded route bitmap");
            complete(0);s=poll();check(s!=null&&s.routeEpoch==2,"no stale route commit");break;
        case "new_texture":
            poll();complete(0);check(poll()!=null,"baseline visible");
            StockTurnTextures.Texture revision=texture(2);revision.source=original.source;select(revision);
            check(poll()!=null,"distance update retains the confirmed native view binding");
            advance(350);poll();complete(0);check(poll().texture==original,"one in-flight old frame holds original without accepting new pixels");
            advance(350);paint(second,revision);poll();complete(0);s=poll();
            check(s!=null && s.texture==revision,"fresh texture confirmed at same turn");break;
        case "distance_stream":
            poll();complete(0);check(poll()!=null,"initial native artwork");
            for(int i=0;i<30;i++){
                advance(350);StockTurnTextures.Texture next=texture(2+i);next.source=original.source;
                select(next);check(poll()!=null,"meters change cannot erase confirmed turn "+i);
                complete(0);check(poll()!=null,"old GPU frame during replacement "+i);
                Arrays.fill(screen.pixels,0);paint(second,next);
                advance(350);poll();complete(0);s=poll();
                check(s!=null && s.texture==next,"new distance positively verified "+i);
            }
            break;
        case "new_owner":
            poll();complete(0);check(poll()!=null,"initial proof");
            select(texture(2));check(poll()==null,"new native view cannot inherit old route binding");break;
        case "copy_failure":
            poll();complete(0);s=poll();
            advance(350);poll();complete(1);check(poll()==s,"one copy failure does not remove visible sign");
            advance(350);poll();complete(0);check(poll()!=null,"capture recovers without disappearance");break;
        case "ambiguous_after_confirmation":
            poll();complete(0);check(poll()!=null,"initial proof");
            paint(first,original);advance(350);poll();complete(0);
            check(poll()==null,"ambiguity never receives a grace period");break;
        case "passed_after_confirmation":
            poll();complete(0);check(poll()!=null,"initial proof");
            frame.routeProgressValid=true;frame.routeSegmentIndex=12;
            check(poll()==null,"passing the confirmed turn clears immediately");break;
        case "motion_stream":
            for(int i=0;i<30;i++){
                window.dx=(i%5)*6;Arrays.fill(screen.pixels,0);paint(second,original);
                if(i>0)advance(350);poll();window.dx+=5;complete(0);s=poll();
                check(s!=null && s.turn==second,"moving capture must renew proof "+i);
                check(s.sampledAt==SystemClock.now,"motion proof timestamp actually renewed");
            }
            break;
        case "swept_ambiguous":
            turns=Arrays.asList(second,turn(12,120,32));poll();window.dx=-16;complete(0);
            check(poll()==null,"two measured sweeps can explain the same visible pixels; reject");break;
        case "expired_transition":
            poll();complete(0);s=poll();
            StockTurnTextures.Texture next=texture(2);next.source=original.source;select(next);
            SystemClock.now+=PrimaryTurnSample.FRESH_MS;
            check(poll()==null,"unconfirmed replacement never extends proof lifetime");break;
        case "changed_during_callback":
            poll();select(texture(2));complete(0);check(poll()==null,"revision changed before callback");break;
        case "confirmed_motion":
            poll();complete(0);s=poll();check(s!=null,"baseline confirmed");
            window.dx=6;check(poll()==s,"camera pan must not erase confirmed route position");
            advance(350);poll();window.dx=10;complete(0);
            check(poll()==s,"in-flight pan retains previous confirmation without renewing it");
            SystemClock.now+=PrimaryTurnSample.FRESH_MS;check(poll()==null,"continued misses cannot renew old evidence");break;
        case "wrong_projection":
            poll();window.dx=40;complete(0);check(poll()==null,"camera moved outside bounded capture sweep");break;
        case "competitor_moved":
            poll();window.alternateDx=40;complete(0);
            check(stored()==null,"moving a competitor invalidates batch even if winning turn stayed still");break;
        case "ambiguous":
            paint(first,original);poll();complete(0);check(poll()==null,"two genuine matching turns left ambiguous");break;
        case "adjacent":
            turns=Arrays.asList(second,turn(12,105,32));poll();complete(0);
            check(poll()==null,"subpixel search can match 2 adjacent points; fail closed");break;
        case "anchor":
            StockTurnTextures.Texture reversed=new StockTurnTextures.Texture(original.bitmap,2,.9375f,.875f,true);
            Arrays.fill(screen.pixels,0);paint(second,reversed);select(reversed);poll();complete(0);s=poll();
            check(s!=null&&s.texture==reversed&&s.turn==second,"noncentral stock texture tip in physical pixels");
            advance(350);Arrays.fill(screen.pixels,0);paint(second,original);poll();complete(0);
            check(poll()!=null,"single incomplete frame retains old confirmation");
            advance(350);poll();complete(0);check(poll()==null,"other orientation cannot confirm this tip");break;
        case "lane_site":
            turns=Collections.emptyList();LaneGuidanceFrame lane=new LaneGuidanceFrame();
            lane.id="site";lane.latitude=second.latitude;lane.longitude=second.longitude;lane.routeSegmentIndex=11;
            StockTurnTextures.Texture maneuver=new StockTurnTextures.Texture(original.bitmap,2,0,0,false);
            select(maneuver);observer.observe(view,window,epoch,turns,lane,frame);
            check(PixelCopy.requests.isEmpty(),"maneuver-only candidate cannot be attached to a route lane site");
            select(original);advance(350);observer.observe(view,window,epoch,turns,lane,frame);complete(0);
            s=observer.observe(view,window,epoch,turns,lane,frame);
            check(s!=null&&s.turn.action.equals("LANE_SITE")&&s.texture.combined,"combined stock sign at exact lane route site");break;
        case "view_hidden":
            poll();PixelCopy.Request hidden=last();view.shown=false;check(poll()==null,"hidden primary map");
            hidden.finish(0);check(hidden.target.isRecycled(),"cancelled capture disposed");
            view.shown=true;advance(350);poll();complete(1);check(poll()==null,"failed copy clears");break;
        case "secure":
            view.activity.w.lp.flags=WindowManager.LayoutParams.FLAG_SECURE;
            check(poll()==null && PixelCopy.requests.isEmpty(),"secure window never read");break;
        case "foreign_surface":
        case "nested_surface":
            check(poll()==null && PixelCopy.requests.isEmpty(),"only the actual SDK primary render buffer is eligible");break;
        case "surface_swap":
            poll();view.children.set(0,new PlatformGLSurfaceView(new Bitmap(window.w,window.h)));
            complete(0);check(stored()==null,"replacement render view in same primary MapView rejects old pixels");break;
        case "buffer_resize":
        case "buffer_destroy":
            poll();SurfaceView surface=(SurfaceView)view.children.get(0);
            check(surface.callbacks.size()==1,"one surface lifecycle subscription");
            if(which.equals("buffer_resize"))surface.signalResize();else surface.signalDestroy();
            finishInvalidated();
            check(stored()==null,"same Java holder with changed native buffer rejects old pixels");
            observer.clear();check(surface.callbacks.isEmpty(),"observer unregisters when route detaches");break;
        case "texture_buffer_swap":
            poll();((TextureView)view.children.get(0)).recreate(new Bitmap(window.w,window.h));
            complete(0);check(stored()==null,"recreated TextureView backing invalidates capture");break;
        case "detached":
            poll();view.attached=false;complete(0);
            check(stored()==null && poll()==null,"detached primary rejects result");break;
        case "passed":
            poll();frame.routeProgressValid=true;frame.routeSegmentIndex=12;
            check(poll()==null,"after turn excluded");last().finish(0);check(poll()==null,"old capture excluded");break;
        case "age":
            poll();SystemClock.now+=1200;complete(0);check(poll()==null,"old pixels must be rejected");break;
        case "budget":
            RouteTurnFrame far=turn(12,1200,640);
            turns=Arrays.asList(second,far);paint(far,original);
            poll();if(!PixelCopy.requests.isEmpty())complete(0);
            check(poll()==null,"multiple genuine matches on separated tiles must stay ambiguous");break;
        case "far_unique":
            RouteTurnFrame unique=turn(12,1200,640);
            turns=Arrays.asList(first,unique);paint(unique,original);
            poll();complete(0);s=poll();
            check(s!=null&&s.turn==unique,"matching distant candidate participates");
            check(PixelCopy.requests.size()==2,"independent small distant readbacks");
            int total=0;for(PixelCopy.Request r:PixelCopy.requests)total+=r.target.w*r.target.h;
            check(total<512*1024,"aggregate readback budget");break;
        case "large_budget":
            select(new StockTurnTextures.Texture(new Bitmap(400,470),2));
            turns=Arrays.asList(turn(12,16,16),turn(13,426,16),turn(14,836,16));
            check(poll()==null&&PixelCopy.requests.isEmpty(),"over-budget candidate set discarded as a whole");break;
        case "hung":
            poll();PixelCopy.Request stalled=last();advance(950);poll();check(last()!=stalled,"retry after stalled capture");
            complete(0);check(poll()!=null,"recovered after stall");
            stalled.finish(1);check(stalled.target.isRecycled()&&poll()!=null,"expired callback cannot hide fresh capture");break;
        case "outstanding":
            poll();PixelCopy.Request oldest=last();advance(950);poll();
            advance(950);poll();check(PixelCopy.requests.size()==2,"at most two real outstanding requests");
            oldest.finish(0);poll();check(PixelCopy.requests.size()==3,"room to retry when old native request returns");break;
        case "busy_worker":
            Bitmap.entered=new java.util.concurrent.CountDownLatch(1);Bitmap.resume=new java.util.concurrent.CountDownLatch(1);
            poll();last().finish(0);
            check(Bitmap.entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"worker started");
            advance(950);poll();last().finish(0);
            advance(950);poll();check(PixelCopy.requests.size()==2,"worker queue and native calls share the cap");
            Bitmap.resume.countDown();
            long deadline=System.nanoTime()+2_000_000_000L;
            while(!PixelCopy.requests.get(1).target.isRecycled() && System.nanoTime()<deadline){Handler.drain();Thread.sleep(1);}
            check(PixelCopy.requests.get(1).target.isRecycled(),"timed out worker released captured bitmap");break;
        case "request_throw":
            PixelCopy.failNext=true;check(poll()==null&&PixelCopy.requests.isEmpty(),"readback failure relinquishes batch");
            advance(350);poll();complete(0);check(poll()!=null,"success following failed request");break;
        case "cached_scope":
            turns=Collections.singletonList(second);poll();complete(0);check(poll()!=null,"first result");
            turns=Arrays.asList(second,turn(12,105,32));
            check(poll()==null,"new competitor immediately invalidates cached result");break;
        case "added_alternative":
            turns=Collections.singletonList(second);poll();
            turns=Arrays.asList(second,turn(12,105,32));poll();
            complete(0);check(stored()==null,"new eligible nearby turn invalidates pending sample");break;
        case "added_texture":
            turns=Collections.singletonList(second);poll();
            StockTurnTextures.byWindow.put(window,Arrays.asList(original,texture(1)));poll();
            complete(0);check(stored()==null,"new interchangeable stock artwork invalidates pending sample");break;
        default: throw new AssertionError(which);
        }
    }
    public static void main(String[] args)throws Exception {new PrimaryTurnReplay(args[0]).run(args[0]);}
}''')


class PrimaryTurnObserverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory()
        out = Path(cls.work.name)
        sources = []
        for name, body in JAVA.items():
            file = out / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(body)
            sources.append(str(file))
        for name in ['PrimaryTurnObserver', 'PrimaryTurnSample', 'StockTurnPixelMatcher', 'ReflectMethods']:
            sources.append(str(MOD / (name + '.java')))
        subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(out), *sources], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def run_case(self, case):
        subprocess.run(['java', '-cp', self.work.name, 'ru.natro.navigation.PrimaryTurnReplay', case], check=True)

    def test_captures_and_lifecycle(self):
        for case in ['distance_stream','new_owner','copy_failure','ambiguous_after_confirmation','passed_after_confirmation','motion_stream','swept_ambiguous','expired_transition','surface','textureView','vulkan','api28','epoch','new_texture','changed_during_callback','foreign_surface','nested_surface',
                     'surface_swap','buffer_resize','buffer_destroy','texture_buffer_swap',
                     'confirmed_motion','wrong_projection','competitor_moved','ambiguous','adjacent','anchor','lane_site','view_hidden','secure','detached','passed','age',
                     'budget','far_unique','large_budget','hung','outstanding','busy_worker','request_throw','cached_scope','added_alternative','added_texture']:
            with self.subTest(case=case):
                self.run_case(case)
