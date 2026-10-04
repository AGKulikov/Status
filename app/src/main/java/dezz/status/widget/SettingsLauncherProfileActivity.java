/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.os.Bundle;
import dezz.status.widget.settings.SettingsAppearance;
/** Settings-only profile host; the actual HOME activity keeps its own rendering policy. */
public abstract class SettingsLauncherProfileActivity extends LauncherProfileActivity {
    @Override protected void onCreate(Bundle state){SettingsAppearance.configure(this);super.onCreate(state);}
    @Override protected void onPostCreate(Bundle state){super.onPostCreate(state);SettingsAppearance.attach(this);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)SettingsAppearance.apply(this,getWindow().getDecorView());}
}
