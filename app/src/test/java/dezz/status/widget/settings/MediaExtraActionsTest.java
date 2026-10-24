package dezz.status.widget.settings;
import android.app.Application;
import android.content.Intent;
import dezz.status.widget.launcher.MediaExtraActions;
import dezz.status.widget.launcher.LauncherShortcutStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class MediaExtraActionsTest {
 @Test public void readyMadeActionsHaveDistinctStableTargets() {
  assertEquals(LauncherShortcutStore.Builtin.MEDIA_LIKE,LauncherShortcutStore.Builtin.fromKey("media_like"));
  assertEquals(LauncherShortcutStore.Builtin.NAVIGATOR_ALICE,LauncherShortcutStore.Builtin.fromKey("navigator_alice"));
 }
}
