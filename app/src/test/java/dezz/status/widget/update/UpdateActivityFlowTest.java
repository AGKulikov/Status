/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

import android.app.Application;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import dezz.status.widget.UpdateActivity;
import java.io.*;
import java.time.Duration;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UpdateActivityFlowTest {
    private final EnumMap<UpdateTarget,Fake> fakes=new EnumMap<>(UpdateTarget.class);
    private Map<UpdateTarget,NatroUpdater> instances;
    private static class Fake extends NatroUpdater {
        int checks,downloads;volatile int intents;boolean available,ready,permission=true,failDownload;
        Fake(){super(RuntimeEnvironment.getApplication());}
        @Override public synchronized JSONObject status()throws Exception{return new JSONObject().put("installed","3.4.8").put("version",available?"3.4.9":"").put("message",ready?"APK проверен":"Готово к проверке").put("busy",false).put("canUpdate",available).put("canInstall",ready);}
        @Override public synchronized void check(){checks++;available=true;}
        @Override public synchronized void download()throws IOException{downloads++;if(failDownload)throw new IOException("Обрыв связи");ready=true;}
        @Override public Intent installationIntent(){intents++;return new Intent(permission?Intent.ACTION_INSTALL_PACKAGE:Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);}
        @Override public boolean canInstallPackages(){return permission;}
    }
    @Before public void setUp(){
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        instances=ReflectionHelpers.getStaticField(NatroUpdater.class,"instances");instances.clear();
        for(UpdateTarget target:UpdateTarget.values()){Fake fake=new Fake();fakes.put(target,fake);instances.put(target,fake);}
    }
    @After public void tearDown(){instances.clear();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));}
    private static List<Button> buttons(View root){List<Button> result=new ArrayList<>();if(root instanceof Button)result.add((Button)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(buttons(((ViewGroup)root).getChildAt(i)));return result;}
    private static Button button(UpdateActivity activity,String text){return buttons(activity.getWindow().getDecorView()).stream().filter(b->text.equals(b.getText().toString())||text.contentEquals(b.getContentDescription()==null?"":b.getContentDescription())).findFirst().orElseThrow(AssertionError::new);}
    private static Intent waitIntent(UpdateActivity activity)throws Exception{
        long deadline=System.nanoTime()+TimeUnitSeconds;
        while(System.nanoTime()<deadline){idle();Intent next=Shadows.shadowOf(activity).getNextStartedActivity();if(next!=null)return next;Thread.sleep(5);}
        throw new AssertionError("Installer was not started");
    }
    private static final long TimeUnitSeconds=java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    @Test public void oneCheckThreeUpdatesAndRealNativeLayout()throws Exception{
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).setup().visible()){
            UpdateActivity activity=controller.get();idle();View view=activity.getWindow().getDecorView();
            button(activity,"Проверить обновления").performClick();idle();
            for(Fake fake:fakes.values())assertEquals(1,fake.checks);
            List<Button> all=buttons(view);assertEquals(3,all.stream().filter(b->b.getText().toString().equals("Обновить")).count());
            assertFalse(all.stream().anyMatch(b->b.getText().toString().contains("Скачать")||b.getText().toString().contains("Установить")));
            view.measure(View.MeasureSpec.makeMeasureSpec(1760,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(656,View.MeasureSpec.EXACTLY));view.layout(0,0,1760,656);
            for(UpdateTarget target:UpdateTarget.values()){Button b=button(activity,"Обновить "+target.title);assertTrue(b.isShown());assertTrue(b.isEnabled());}
            File dir=new File("build/settings-screenshots");assertTrue(dir.isDirectory()||dir.mkdirs());Bitmap image=Bitmap.createBitmap(1760,656,Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));
            try(OutputStream out=new FileOutputStream(new File(dir,"update-center-349.png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();
        }
    }
    @Test public void oneTapDownloadsAndOpensOnlySelectedAppWithoutReopenAfterCancel()throws Exception{
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).setup().visible()){
            UpdateActivity activity=controller.get();idle();button(activity,"Проверить обновления").performClick();button(activity,"Обновить Музыка").performClick();
            assertEquals(Intent.ACTION_INSTALL_PACKAGE,waitIntent(activity).getAction());assertEquals(1,fakes.get(UpdateTarget.MUSIC).downloads);assertEquals(0,fakes.get(UpdateTarget.NATRO).downloads);
            controller.pause().resume();idle();assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertEquals(1,fakes.get(UpdateTarget.MUSIC).intents);
            button(activity,"Обновить Музыка").performClick();assertEquals(Intent.ACTION_INSTALL_PACKAGE,waitIntent(activity).getAction());assertEquals(1,fakes.get(UpdateTarget.MUSIC).downloads);
        }
    }
    @Test public void permissionReturnContinuesWithoutAnotherDownload()throws Exception{
        Fake nav=fakes.get(UpdateTarget.NAVIGATOR);nav.permission=false;
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).setup().visible()){
            UpdateActivity activity=controller.get();idle();button(activity,"Проверить обновления").performClick();button(activity,"Обновить Навигатор").performClick();
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,waitIntent(activity).getAction());controller.pause();nav.permission=true;controller.resume();
            assertEquals(Intent.ACTION_INSTALL_PACKAGE,waitIntent(activity).getAction());assertEquals(1,nav.downloads);
        }
    }
    @Test public void deniedPermissionDoesNotLoopAndDownloadFailureCanRetry()throws Exception{
        Fake music=fakes.get(UpdateTarget.MUSIC);music.failDownload=true;
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).setup().visible()){
            UpdateActivity activity=controller.get();idle();button(activity,"Проверить обновления").performClick();button(activity,"Обновить Музыка").performClick();idle();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertTrue(button(activity,"Обновить Музыка").isEnabled());
            music.failDownload=false;music.permission=false;button(activity,"Обновить Музыка").performClick();assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,waitIntent(activity).getAction());
            controller.pause().resume();idle();assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertTrue(button(activity,"Обновить Музыка").isEnabled());
        }
    }
}
