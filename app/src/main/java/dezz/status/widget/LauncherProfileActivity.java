/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.launcher.PassengerHomeLauncher;

/** Carries the HOME profile through the existing contextual editors and their return paths. */
public abstract class LauncherProfileActivity extends AppCompatActivity {
    public static final String EXTRA_PASSENGER_PROFILE = "dezz.status.widget.extra.PASSENGER_HOME";

    public boolean isPassengerLauncherProfile() {
        return getIntent() != null && getIntent().getBooleanExtra(EXTRA_PASSENGER_PROFILE, false);
    }

    protected final Preferences createLauncherPreferences(boolean migrate) {
        return Preferences.forLauncher(this, isPassengerLauncherProfile(), migrate);
    }

    private Intent passengerIntent(Intent source) {
        Intent intent = new Intent(source);
        ComponentName component = intent.getComponent();
        if (component != null && getPackageName().equals(component.getPackageName())) {
            String name = component.getClassName();
            if (name.equals(LauncherActivity.class.getName())) {
                intent.setClass(this, PassengerLauncherActivity.class);
            } else if (name.equals(LauncherSettingsActivity.class.getName())) {
                intent.setClass(this, PassengerLauncherSettingsActivity.class);
            }
            intent.putExtra(EXTRA_PASSENGER_PROFILE, true);
        }
        return intent;
    }

    @Override public void startActivity(Intent intent) { startActivity(intent, null); }

    @Override public void startActivity(Intent intent, Bundle options) {
        if (!isPassengerLauncherProfile()) { super.startActivity(intent, options); return; }
        try {
            super.startActivity(passengerIntent(intent), PassengerHomeLauncher.options(this, options));
        } catch (RuntimeException error) { PassengerHomeLauncher.reportFailure(this, error); }
    }

    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (!isPassengerLauncherProfile()) {
            super.startActivityForResult(intent, requestCode, options); return;
        }
        try {
            super.startActivityForResult(passengerIntent(intent), requestCode,
                    PassengerHomeLauncher.options(this, options));
        } catch (RuntimeException error) { PassengerHomeLauncher.reportFailure(this, error); }
    }
}
