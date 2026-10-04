/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
public abstract class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){SettingsAppearance.configure(this);super.onCreate(state);}
    @Override protected void onPostCreate(Bundle state){super.onPostCreate(state);SettingsAppearance.attach(this);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)SettingsAppearance.apply(this,getWindow().getDecorView());}
}
