/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.launcher.media.*;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, qualifiers="mdpi")
public class MediaTouchRoutingTest {
    private Context context;
    private ActivityController<Activity> host;
    private FrameLayout sources;
    private long clock = 1000;
    @Before public void attachRealSourceHierarchy() {
        host=Robolectric.buildActivity(Activity.class);
        host.get().setTheme(R.style.Theme_StatusWidget);
        host.create().start().resume().visible();
        context=host.get(); sources=new FrameLayout(context);
        host.get().setContentView(sources);
    }
    @After public void closeHost() { if(host!=null)host.pause().stop().destroy(); }
    private void attachSource(View view,int width,int height) {
        if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
        sources.addView(view,new FrameLayout.LayoutParams(width,height));
        size(view,width,height);
        // View.post(click) on an unattached source waits for attachment; real HOME keeps its
        // invisible source panels attached. The test must exercise that same lifecycle.
        assertTrue(view.isAttachedToWindow());
    }
    private void size(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private boolean event(View view, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(clock, clock + 20, action, x, y, 0);
        try { return view.dispatchTouchEvent(e); }
        finally { e.recycle(); clock += 30; Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
    private void tap(View view, float x, float y) {
        assertTrue(event(view, MotionEvent.ACTION_DOWN, x, y));
        event(view, MotionEvent.ACTION_UP, x, y);
    }
    private MediaControlButton button(String id, int[] calls, int index) {
        MediaControlButton button = new MediaControlButton(context);
        GradientDrawable icon = new GradientDrawable(); icon.setColor(Color.WHITE); icon.setSize(40,40);
        button.setImageDrawable(icon); button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(20,20,20,20); button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(v -> calls[index]++);
        LauncherGlobalElementTag.attach(button, LauncherLayoutStore.MEDIA, id, id);
        attachSource(button, 200, 80);
        return button;
    }
    private LauncherElementFrame frame(View source, LauncherGlobalElementLayoutStore.Appearance appearance) {
        LauncherGlobalElementProxyView proxy = new LauncherGlobalElementProxyView(context, () -> source, appearance);
        LauncherElementFrame frame = new LauncherElementFrame(context,"test","test",(id,x,y,w,h)->{});
        frame.setContent(proxy);
        frame.setOnClickListener(v -> fail("Runtime must not invoke editor"));
        frame.setEditMode(false,1);
        frame.setLayoutParams(new FrameLayout.LayoutParams(200,80));
        return frame;
    }
    private void place(FrameLayout root, View child, int x, int y, int width, int height) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width,height);
        p.leftMargin=x; p.topMargin=y; root.addView(child,p);
    }
    @Test public void overlappingFramesDispatchToVisibleIconAtEveryScaleAndCancelWithoutRetargeting() {
        for (LauncherGlobalElementLayoutStore.ScaleMode mode : LauncherGlobalElementLayoutStore.ScaleMode.values()) {
          for (int[] extent : new int[][]{{200,80},{100,40},{240,120}}) {
            int width=extent[0],height=extent[1]; float first=width/2f+10f, second=width, y=height/2f;
            int[] calls={0,0}; FrameLayout root=new FrameLayout(context);
            LauncherGlobalElementLayoutStore.Appearance appearance=new LauncherGlobalElementLayoutStore.Appearance();
            appearance.scaleMode=mode;
            place(root,frame(button(MediaPanelConfig.PLAY_PAUSE,calls,0),appearance),0,0,width,height);
            place(root,frame(button(MediaPanelConfig.NEXT,calls,1),appearance),width/2,0,width,height);
            size(root,width*2,height*2);
            tap(root,first,y); assertArrayEquals(new int[]{1,0},calls);
            tap(root,second,y); assertArrayEquals(new int[]{1,1},calls);
            event(root,MotionEvent.ACTION_DOWN,first,y);
            event(root,MotionEvent.ACTION_MOVE,second,y);
            event(root,MotionEvent.ACTION_UP,second,y);
            assertArrayEquals(new int[]{1,1},calls);
            event(root,MotionEvent.ACTION_DOWN,second,y);
            event(root,MotionEvent.ACTION_CANCEL,second,y);
            assertArrayEquals(new int[]{1,1},calls);
          }
        }
    }
    @Test public void fitLetterboxAndTitleBlankHeightPassThroughRealFrame() {
        int[] calls={0,0}; FrameLayout root=new FrameLayout(context);
        LauncherGlobalElementLayoutStore.Appearance appearance=new LauncherGlobalElementLayoutStore.Appearance();
        place(root,frame(button(MediaPanelConfig.PLAY_PAUSE,calls,0),appearance),0,0,200,80);
        TextView title=new TextView(context); title.setText("Заходит Луна"); title.setSingleLine(true);
        title.setTextSize(16); title.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        LauncherGlobalElementTag.attach(title,LauncherLayoutStore.MEDIA,MediaPanelConfig.TITLE,"title");
        attachSource(title,200,100); place(root,frame(title,appearance),0,0,200,100);
        size(root,250,150); tap(root,100,40); assertEquals(1,calls[0]);
        root.removeViewAt(1);
        place(root,frame(button(MediaPanelConfig.NEXT,calls,1),appearance),0,0,200,150);
        size(root,250,160); tap(root,100,20); assertArrayEquals(new int[]{2,0},calls);
    }
    @Test public void directButtonRejectsEmptyCellButKeepsSmallGlyphUsable() {
        int[] calls={0}; MediaControlButton button=button(MediaPanelConfig.PLAY_PAUSE,calls,0);
        assertFalse(event(button,MotionEvent.ACTION_DOWN,10,40));
        tap(button,100,40); assertEquals(1,calls[0]);
        assertFalse(event(button,MotionEvent.ACTION_DOWN,190,40));
    }
    @Test public void actualProgressOnlyStartsOnTrackAndEditorStillOwnsWholeFrame() {
        int[] calls={0,0};
        MediaPanelView panel=new MediaPanelView(context,new MediaPanelConfigStore(new Preferences(context)),new MediaPanelView.Controls(){
            public void previous(){} public void playPause(){} public void next(){}
            public void setVolumePercent(int value){} public boolean like(){return true;}
            public void seekTo(long value){calls[0]++;} public void finishSeek(long value){calls[1]++;}
            public boolean openPlayer(){fail("Progress blank space must not open the player");return false;}
        });
        panel.setSnapshot(new LauncherMediaController.Snapshot("ⓘ Song","Artist","","Music",null,100000,0,true,true,true,false,40));
        size(panel,800,600);
        View progress=find(panel,LauncherLayoutStore.MEDIA+"/"+MediaPanelConfig.PROGRESS);
        assertNotNull(progress); attachSource(progress,240,80);
        LauncherElementFrame frame=frame(progress,new LauncherGlobalElementLayoutStore.Appearance()); size(frame,240,80);
        assertFalse(event(frame,MotionEvent.ACTION_DOWN,120,4)); assertEquals(0,calls[0]);
        tap(frame,120,70); assertTrue(calls[0]>0); assertEquals(1,calls[1]);
        frame.setEditMode(true,1); frame.setOnClickListener(v -> calls[1]++);
        assertTrue(event(frame,MotionEvent.ACTION_DOWN,120,4));
        event(frame,MotionEvent.ACTION_CANCEL,120,4); assertEquals(1,calls[1]);
    }
    private View find(View view,String id) {
        LauncherGlobalElementTag tag=LauncherGlobalElementTag.from(view);
        if(tag!=null&&id.equals(tag.id))return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=find(((ViewGroup)view).getChildAt(i),id); if(found!=null)return found;
        }
        return null;
    }
}
