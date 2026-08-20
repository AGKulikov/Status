/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * Transparent result owner for Android's standard uninstall confirmation.
 *
 * <p>Runtime app drawers are WindowManager overlays, not activities. Keeping this tiny activity
 * below Package Installer lets the drawer stay attached and return to the same edit session even
 * when the user cancels removal.</p>
 */
public final class AppUninstallProxyActivity extends Activity {
    private static final int REQUEST_UNINSTALL = 7311;
    private boolean launched;
    private boolean completionSent;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launched = savedInstanceState != null
                && savedInstanceState.getBoolean("launched", false);
        if (launched) return;
        launched = true;
        String packageName = getIntent().getStringExtra(
                AppUninstallLauncher.EXTRA_PACKAGE_NAME);
        String label = getIntent().getStringExtra(AppUninstallLauncher.EXTRA_LABEL);
        if (packageName == null || packageName.trim().isEmpty()) {
            finishAndNotify();
            return;
        }
        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.fromParts("package", packageName.trim(), null))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(uninstall, REQUEST_UNINSTALL);
        } catch (RuntimeException failure) {
            Toast.makeText(this,
                    "Удаление недоступно для «" + (label == null ? packageName : label) + "»",
                    Toast.LENGTH_LONG).show();
            finishAndNotify();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("launched", launched);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL) finishAndNotify();
    }

    private void finishAndNotify() {
        if (!completionSent) {
            completionSent = true;
            sendBroadcast(new Intent(AppUninstallLauncher.ACTION_FINISHED)
                    .setPackage(getPackageName()));
        }
        finish();
    }
}
