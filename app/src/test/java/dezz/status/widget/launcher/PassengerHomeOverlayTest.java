/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import dezz.status.widget.*;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.NoCarIntegration;
import dezz.status.widget.settings.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real Android window/service/context routing. Physical ECARX composition remains a separate gate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class PassengerHomeOverlayTest {
    private Application app;
    private Display passenger;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        ReflectionHelpers.setStaticField(CarIntegrations.class,"instance",new NoCarIntegration());
        ShadowSettings.setCanDrawOverlays(true);
        assertEquals(1,ShadowDisplayManager.addDisplay("w800dp-h400dp-land-mdpi"));
        assertEquals(2,ShadowDisplayManager.addDisplay("w800dp-h400dp-land-mdpi"));
        assertEquals(3,ShadowDisplayManager.addDisplay("w1760dp-h656dp-land-mdpi"));
        passenger=app.getSystemService(DisplayManager.class).getDisplay(3);
    }
    private static int display(Bundle options){
        ActivityOptions decoded=ReflectionHelpers.callConstructor(ActivityOptions.class,ReflectionHelpers.ClassParameter.from(Bundle.class,options));
        return decoded.getLaunchDisplayId();
    }
    private static LauncherHomeSurface surface(PassengerHomeWindow window){return ReflectionHelpers.getField(window,"surface");}
    private static PassengerHomeWindow window(PassengerHomeService service){return ReflectionHelpers.getField(service,"window");}
    private static Intent request(boolean editor){
        return new Intent().putExtra(PassengerHomeService.EXTRA_REQUEST,new Intent(Intent.ACTION_MAIN)
                .putExtra(LauncherActivity.EXTRA_EDIT_MODE,editor));
    }
    private static class Owner implements PassengerHomeWindow.Owner {
        int external,closed,recreated;
        public void recreate(PassengerHomeWindow window){recreated++;}
        public void closed(PassengerHomeWindow window){closed++;}
        public void launchedExternal(PassengerHomeWindow window){external++;}
        public void displayInvalidated(PassengerHomeWindow window){recreated++;}
    }
    @Test public void homeSubmissionStartsOnlyItsServiceAndReportsDeniedSubmission() {
        class RecordingContext extends ContextWrapper {
            int calls; boolean denied; Intent last;
            RecordingContext(){super(app);}
            @Override public Context getApplicationContext(){return this;}
            @Override public ComponentName startForegroundService(Intent intent){
                calls++;last=intent;if(denied)throw new SecurityException("denied");
                return intent.getComponent();
            }
            @Override public void startActivity(Intent intent){fail("HOME must not create or move an Activity");}
            @Override public void startActivity(Intent intent,Bundle options){fail("HOME must not create or move an Activity");}
        }
        RecordingContext context=new RecordingContext();
        assertTrue(PassengerHomeLauncher.open(context));assertEquals(1,context.calls);
        assertEquals(PassengerHomeService.class.getName(),context.last.getComponent().getClassName());
        context.denied=true;assertFalse(PassengerHomeLauncher.open(context));assertEquals(2,context.calls);
        context.denied=false;assertTrue(PassengerHomeLauncher.open(context));assertEquals(3,context.calls);
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void explicitDisplayOptionsPreserveCallerBundleAndNeverFallback() {
        Bundle original=ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle();
        original.putString("custom","transition");
        Bundle result=PassengerHomeLauncher.options(app,original);
        assertEquals(3,display(result));
        assertEquals(0,display(original));
        assertEquals("transition",result.getString("custom"));
        Shadows.shadowOf(passenger).setState(Display.STATE_OFF);
        assertThrows(IllegalStateException.class,()->PassengerHomeLauncher.options(app,original));
        Shadows.shadowOf(passenger).setState(Display.STATE_ON);
        assertEquals(3,display(PassengerHomeLauncher.options(app,null)));
        ShadowDisplayManager.removeDisplay(3);
        assertFalse(passenger.isValid());
        assertThrows(IllegalStateException.class,()->PassengerHomeLauncher.options(app,null));
        assertThrows(IllegalArgumentException.class,()->PassengerHomeLauncher.targetOptions(app,null,2));
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void realPassengerWindowSharesFullRendererWithoutMovingDriverActivity() {
        Owner owner=new Owner();
        try(ActivityController<LauncherActivity> driver=Robolectric.buildActivity(LauncherActivity.class)) {
            LauncherActivity main=driver.setup().get();
            PassengerHomeWindow pass=new PassengerHomeWindow(app,passenger,new Intent(Intent.ACTION_MAIN),null,owner);
            try {
                pass.show();
                assertEquals(3,pass.getDisplay().getDisplayId());
                assertEquals(3,pass.windowManager().getDefaultDisplay().getDisplayId());
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,pass.getWindow().getAttributes().type);
                assertEquals(0,main.getWindowManager().getDefaultDisplay().getDisplayId());
                assertFalse(main.isFinishing());
                LauncherHomeSurface mainSurface=ReflectionHelpers.getField(main,"surface");
                assertEquals(mainSurface.getClass(),surface(pass).getClass());
                assertNotNull(ReflectionHelpers.getField(surface(pass),"workspace"));
                assertTrue(LauncherProfile.passenger(surface(pass)));
                assertFalse(LauncherProfile.passenger(mainSurface));
                assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            } finally {pass.dismiss();}
        }
        assertThrows(IllegalArgumentException.class,()->new PassengerHomeWindow(app,
                app.getSystemService(DisplayManager.class).getDisplay(0),new Intent(),null,owner));
    }
    @Test public void settingsRemainOnMainAndExternalAppsAreExplicitlyOnPassenger() {
        Owner owner=new Owner();
        PassengerHomeWindow pass=new PassengerHomeWindow(app,passenger,new Intent(),null,owner);
        try {
            pass.show();
            surface(pass).startActivity(new Intent(surface(pass),LauncherSettingsActivity.class));
            ShadowActivity.IntentForResult settings=Shadows.shadowOf(app).getNextStartedActivityForResult();
            assertEquals(PassengerLauncherSettingsActivity.class.getName(),settings.intent.getComponent().getClassName());
            assertTrue(settings.intent.getBooleanExtra(LauncherProfileActivity.EXTRA_PASSENGER_PROFILE,false));
            assertEquals(0,display(settings.options));
            assertTrue(pass.isShowing());assertEquals(0,owner.external);
            Intent external=new Intent().setComponent(new ComponentName("sample.player","sample.player.Main"));
            surface(pass).startActivity(external);
            ShadowActivity.IntentForResult launched=Shadows.shadowOf(app).getNextStartedActivityForResult();
            assertEquals(external.getComponent(),launched.intent.getComponent());
            assertEquals(3,display(launched.options));
            assertTrue((launched.intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0);
            assertEquals(1,owner.external);
            assertEquals(3,PassengerHomeLauncher.targetDisplay(app,new Intent(app,AppUninstallProxyActivity.class)));
        } finally {pass.dismiss();}
    }
    @Test public void childDialogsStayOnPassengerAndCloseWithTheirWindow() {
        PassengerHomeWindow pass=new PassengerHomeWindow(app,passenger,new Intent(),null,new Owner());
        pass.show();
        LauncherHomeSurface renderer=surface(pass);
        AlertDialog dialog=new SettingsDialogBuilder(renderer).setTitle("Проверка").setPositiveButton("OK",null).show();
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,dialog.getWindow().getAttributes().type);
        assertEquals(3,((WindowManager)dialog.getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getDisplayId());
        assertTrue(dialog.isShowing());
        pass.dismiss();
        assertFalse(dialog.isShowing());assertTrue(renderer.windowOwnerDestroyed());
    }
    @Test public void displayOffRetainsDraftAndOldControlsCannotWriteOnReturn() {
        ServiceController<PassengerHomeService> controller=Robolectric.buildService(PassengerHomeService.class).create();
        PassengerHomeService service=controller.get();
        try {
            service.onStartCommand(request(true),0,1);
            PassengerHomeWindow before=window(service);assertNotNull(before);
            LauncherHomeSurface oldSurface=surface(before);
            SettingsEditSession draft=SettingsEditSession.find(oldSurface);assertNotNull(draft);
            Preferences original=Preferences.forPassengerLauncher(app);
            String durable=original.launcherBackgroundColor.get();
            Preferences controls=Preferences.forPassengerLauncher(oldSurface);
            controls.launcherBackgroundColor.set("#123456");assertTrue(draft.dirty());
            Shadows.shadowOf(passenger).setState(Display.STATE_OFF);before.cancel();
            assertNull(window(service));assertFalse(before.isShowing());assertFalse(draft.isClosed());
            assertTrue(SettingsEditSession.find(oldSurface).isClosed());
            controls.launcherBackgroundColor.set("#badbad");
            Preferences.forPassengerLauncher(oldSurface).launcherBackgroundColor.set("#badbad");
            Shadows.shadowOf(passenger).setState(Display.STATE_ON);service.onDisplayChanged(3);
            PassengerHomeWindow after=window(service);assertNotNull(after);assertNotSame(before,after);
            assertSame(draft,SettingsEditSession.find(surface(after)));
            assertEquals("#123456",Preferences.forPassengerLauncher(surface(after)).launcherBackgroundColor.get());
            service.recreate(before);service.closed(before);service.launchedExternal(before);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(after,window(service));
            draft.cancel(surface(after));
            assertEquals(durable,original.launcherBackgroundColor.get());
        } finally {controller.destroy();}
    }
    @Test public void externalLaunchHidesHomeAndExplicitHomeRecoversUnsavedDraft() {
        ServiceController<PassengerHomeService> controller=Robolectric.buildService(PassengerHomeService.class).create();
        PassengerHomeService service=controller.get();
        try {
            service.onStartCommand(request(true),0,1);
            PassengerHomeWindow before=window(service);
            Preferences.forPassengerLauncher(surface(before)).launcherBackgroundColor.set("#456789");
            SettingsEditSession draft=SettingsEditSession.find(surface(before));
            before.launch(new Intent().setComponent(new ComponentName("sample.player","sample.player.Main")),-1,null);
            assertNull(window(service));assertFalse(before.isShowing());assertFalse(draft.isClosed());
            service.onDisplayChanged(3);assertNull(window(service));
            service.onStartCommand(request(false),0,2);
            assertSame(draft,SettingsEditSession.find(surface(window(service))));
            assertEquals("#456789",Preferences.forPassengerLauncher(surface(window(service))).launcherBackgroundColor.get());
        } finally {controller.destroy();}
    }
    @Test public void unavailableDisplayAndPermissionNeverCreateAMainWindow() {
        ShadowDisplayManager.removeDisplay(3);
        ServiceController<PassengerHomeService> controller=Robolectric.buildService(PassengerHomeService.class).create();
        PassengerHomeService service=controller.get();
        try {
            service.onStartCommand(request(false),0,1);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(65));
            assertNull(window(service));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            ShadowSettings.setCanDrawOverlays(false);
            service.onStartCommand(request(false),0,2);
            assertNull(window(service));assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        } finally {controller.destroy();}
    }
}
