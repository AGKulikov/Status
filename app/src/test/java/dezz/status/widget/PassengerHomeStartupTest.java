/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.os.UserManager;
import dezz.status.widget.launcher.PassengerHomeService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class PassengerHomeStartupTest {
    public static class Editor extends android.app.Activity {}
    private static final class RecordingContext extends ContextWrapper {
        int starts;
        RecordingContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public ComponentName startForegroundService(Intent intent) {
            assertEquals(PassengerHomeService.class.getName(), intent.getComponent().getClassName());
            starts++; return intent.getComponent();
        }
        @Override public void startActivity(Intent intent) { fail("Must not move driver's task"); }
    }
    @Test public void optInStartsOnceWithoutWaitingForPassengerDisplay() {
        RecordingContext context = new RecordingContext();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        new Preferences(context, false).passengerLauncherAutoStart.set(true);
        PassengerHomeStartup startup = new PassengerHomeStartup();
        startup.onUnlockedStart(context, true);
        assertEquals(0, context.starts);
        startup.onUnlockedStart(context, false);
        startup.onUnlockedStart(context, false);
        assertEquals(1, context.starts);
        // A fresh process gets a fresh decision; ordinary reconcile does not.
        new PassengerHomeStartup().onUnlockedStart(context, false);
        assertEquals(2, context.starts);
    }
    @Test public void disabledDecisionCannotBeReplayedByApplyingSettings() throws Exception {
        RecordingContext context = new RecordingContext();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        Preferences prefs = new Preferences(context, false);
        assertFalse(prefs.passengerLauncherAutoStart.get());
        assertEquals(Boolean.FALSE, PortableBackupDefaults.capture(context).get("passengerLauncherAutoStart"));
        PassengerHomeStartup startup = new PassengerHomeStartup();
        startup.onUnlockedStart(context, false);
        prefs.passengerLauncherAutoStart.set(true);
        startup.onUnlockedStart(context, false);
        assertEquals(0, context.starts);
        assertTrue(Preferences.forPassengerLauncher(context).passengerLauncherAutoStart.get());
    }
    @Test public void lockedBootDefersDecisionUntilUnlock() {
        RecordingContext context = new RecordingContext();
        new Preferences(context, false).passengerLauncherAutoStart.set(true);
        PassengerHomeStartup startup = new PassengerHomeStartup();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);
        startup.onUnlockedStart(context, false);
        assertEquals(0, context.starts);
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        startup.onUnlockedStart(context, false);
        assertEquals(1, context.starts);
    }
    @Test public void liveDraftCannotLaunchHomeAndCancelKeepsDefault() {
        RecordingContext context = new RecordingContext();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        try(org.robolectric.android.controller.ActivityController<Editor> controller =
                    org.robolectric.Robolectric.buildActivity(Editor.class).setup()) {
            Editor editor = controller.get();
            dezz.status.widget.settings.SettingsEditSession draft =
                    dezz.status.widget.settings.SettingsEditSession.beginEditor(editor, null);
            Preferences controls = new Preferences(editor, false);
            controls.passengerLauncherAutoStart.set(true);
            assertTrue(controls.passengerLauncherAutoStart.get());
            assertFalse(new Preferences(context, false).passengerLauncherAutoStart.get());
            new PassengerHomeStartup().onUnlockedStart(context, false);
            assertEquals(0, context.starts);
            draft.cancel(editor);
            assertFalse(new Preferences(context, false).passengerLauncherAutoStart.get());
        }
    }
}
