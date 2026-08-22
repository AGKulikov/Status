/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import dezz.status.widget.phone.liveactivity.ApnsCredentialStore;
import dezz.status.widget.phone.liveactivity.LiveActivityProvisioningStore;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Post-install APNs key import. The .p8 bytes are encrypted and never copied into preferences. */
public final class LiveActivityApnsSettingsActivity extends AppCompatActivity {
    private static final int REQUEST_P8 = 0xA963;
    private static final int MAX_IMPORT_BYTES = 16 * 1024;

    private ApnsCredentialStore credentials;
    private LiveActivityProvisioningStore provisioning;
    private EditText teamId;
    private EditText keyId;
    private EditText topic;
    private MaterialSwitch production;
    private TextView status;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        credentials = new ApnsCredentialStore(this);
        provisioning = new LiveActivityProvisioningStore(this);
        View screen = buildScreen();
        setContentView(screen);
        SettingsBackNavigation.install(this, screen);
        refresh();
    }

    @NonNull private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.settings_background));
        LinearLayout page = column();
        page.setPadding(dp(24), dp(22), dp(24), dp(50));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        page.addView(label("APNs для Live Activity", 30, Typeface.BOLD));
        TextView explanation = label(
                "Ключ импортируется после установки, шифруется Android Keystore и не входит "
                        + "в APK, Git или резервную копию. Используйте отдельный Topic Specific "
                        + "ключ только для Helper.", 14, Typeface.NORMAL);
        explanation.setTextColor(getColor(R.color.settings_secondary_text));
        page.addView(explanation, margin(7));

        teamId = field("Apple Team ID (10 символов)");
        keyId = field("APNs Key ID (10 символов)");
        topic = field("Live Activity topic");
        topic.setText(credentials.topic());
        page.addView(teamId, margin(22));
        page.addView(keyId, margin(10));
        page.addView(topic, margin(10));

        production = new MaterialSwitch(this);
        production.setText("Production APNs");
        production.setTextColor(getColor(android.R.color.black));
        production.setTextSize(17);
        production.setPadding(dp(8), dp(12), dp(8), dp(12));
        page.addView(production, margin(8));

        MaterialButton importButton = button("Выбрать и зашифровать .p8");
        importButton.setOnClickListener(view -> chooseP8());
        page.addView(importButton, margin(14));

        MaterialButton removeButton = button("Удалить APNs-ключ с магнитолы");
        removeButton.setOnClickListener(view -> confirmRemove());
        page.addView(removeButton, margin(9));

        status = label("", 14, Typeface.NORMAL);
        status.setTextColor(getColor(R.color.settings_secondary_text));
        status.setPadding(dp(8), dp(14), dp(8), dp(8));
        page.addView(status);
        return scroll;
    }

    private void chooseP8() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, REQUEST_P8);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                              @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_P8 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        byte[] pem = null;
        try {
            pem = readBounded(uri);
            credentials.save(teamId.getText().toString(), keyId.getText().toString(),
                    topic.getText().toString(), production.isChecked(), pem);
            notifyRuntime();
            refresh();
            Toast.makeText(this, "APNs-ключ зашифрован", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("Ключ не сохранён")
                    .setMessage(error.getMessage() == null ? "Проверьте .p8 и идентификаторы"
                            : error.getMessage())
                    .setPositiveButton(android.R.string.ok, null).show();
        } finally {
            if (pem != null) java.util.Arrays.fill(pem, (byte) 0);
        }
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this).setTitle("Удалить APNs-ключ?")
                .setMessage("Push-to-start с магнитолы перестанет работать, пока ключ не будет "
                        + "импортирован снова.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    credentials.clear();
                    notifyRuntime();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void refresh() {
        teamId.setText(credentials.teamId());
        keyId.setText(credentials.keyId());
        topic.setText(credentials.topic());
        production.setChecked(credentials.production());
        boolean keyReady = credentials.isConfigured();
        boolean helperReady = provisioning.readyForStart();
        status.setText("APNs-ключ: " + (keyReady ? "готов" : "не настроен")
                + "\nТокен и конфигурация Helper: "
                + (helperReady ? "получены" : "ожидаются при следующем Bluetooth-подключении")
                + "\nPush-to-start: " + (keyReady && helperReady ? "готов" : "не готов"));
    }

    private void notifyRuntime() {
        WidgetService service = WidgetService.getInstance();
        if (service != null) service.applyPreferences();
        else WidgetServiceStarter.startIfNeeded(this);
    }

    @NonNull private byte[] readBounded(@NonNull Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Файл недоступен");
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Файл .p8 слишком большой");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    @NonNull private EditText field(@NonNull String hint) {
        EditText result = new EditText(this);
        result.setHint(hint);
        result.setSingleLine(true);
        result.setTextColor(getColor(android.R.color.black));
        result.setHintTextColor(getColor(R.color.settings_tertiary_text));
        result.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        result.setPadding(dp(14), dp(10), dp(14), dp(10));
        return result;
    }

    @NonNull private MaterialButton button(@NonNull String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(52));
        return button;
    }

    @NonNull private TextView label(String text, int sp, int style) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sp);
        label.setTypeface(Typeface.DEFAULT, style);
        label.setTextColor(getColor(android.R.color.black));
        return label;
    }

    @NonNull private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    @NonNull private LinearLayout.LayoutParams margin(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
