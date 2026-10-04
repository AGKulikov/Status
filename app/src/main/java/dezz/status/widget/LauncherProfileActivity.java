/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.launcher.PassengerHomeLauncher;

/** Carries the HOME profile through the existing contextual editors and their return paths. */
public abstract class LauncherProfileActivity extends AppCompatActivity implements dezz.status.widget.launcher.LauncherProfile {
    public static final String EXTRA_PASSENGER_PROFILE = "dezz.status.widget.extra.PASSENGER_HOME";

    public boolean isPassengerLauncherProfile() {
        return getIntent() != null && getIntent().getBooleanExtra(EXTRA_PASSENGER_PROFILE, false);
    }

    protected final Preferences createLauncherPreferences(boolean migrate) {
        return Preferences.forLauncher(this, isPassengerLauncherProfile(), migrate);
    }

    @Override public void startActivity(Intent intent) { startActivity(intent, null); }

    @Override public void startActivity(Intent intent, Bundle options) {
        if (!isPassengerLauncherProfile()) { super.startActivity(intent, options); return; }
        if(PassengerHomeLauncher.isHomeIntent(intent)){PassengerHomeLauncher.open(this,intent);return;}
        try {
            super.startActivity(PassengerHomeLauncher.profileIntent(this,intent), PassengerHomeLauncher.targetOptions(
                    this, options, PassengerHomeLauncher.targetDisplay(this,intent)));
        } catch (RuntimeException error) { PassengerHomeLauncher.reportFailure(this, error); }
    }

    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (!isPassengerLauncherProfile()) {
            super.startActivityForResult(intent, requestCode, options); return;
        }
        if(PassengerHomeLauncher.isHomeIntent(intent)){PassengerHomeLauncher.open(this,intent);return;}
        try {
            super.startActivityForResult(PassengerHomeLauncher.profileIntent(this,intent), requestCode,
                    PassengerHomeLauncher.targetOptions(this,options,PassengerHomeLauncher.targetDisplay(this,intent)));
        } catch (RuntimeException error) { PassengerHomeLauncher.reportFailure(this, error); }
    }
}
