/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.widget.Toast;
import dezz.status.widget.car.CarIntegrations;

/** Foreground confirmation/Android permission UI used by background button assignments. */
public final class ButtonPromptActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("phone".equals(getIntent().getStringExtra("operation"))) {
            requestPermissions(new String[]{"android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE"}, 1);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Перезагрузить систему?")
                .setMessage("Мультимедийная система будет перезапущена.")
                .setNegativeButton("Отмена", (dialog, which) -> finish())
                .setPositiveButton("Перезагрузить", (dialog, which) -> {
                    CarIntegrations.get(this).restartInfotainment((ok, detail) -> {
                        if (!ok) Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
                    });
                    finish();
                }).setOnCancelListener(dialog -> finish()).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants);
        if (code == 1 && grants.length == 2 && grants[0] == PackageManager.PERMISSION_GRANTED
                && grants[1] == PackageManager.PERMISSION_GRANTED) {
            VehicleButtonController.get(this).callNumber(getIntent().getStringExtra("number"));
        }
        finish();
    }
}
