/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import dezz.status.widget.diagnostics.NavigatorInstallDiagnostics;
import dezz.status.widget.diagnostics.NavigatorInstallPolicy;

/** A manual diagnostic path for the actual head-unit installer; no computer is required. */
public final class NavigatorInstallDiagnosticsActivity extends AppCompatActivity {
    private static final int SELECT_APK = 841, INSTALL_PERMISSION = 842;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, details;
    private Button select, install, cancel;
    private String displayedSummary = "", displayedReport = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshScreen(); main.postDelayed(this, 750); }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20); page.setPadding(padding, padding, padding, dp(36));
        scroll.addView(page);
        Button back = button(page, "‹ Назад"); back.setOnClickListener(v -> finish());
        TextView title = text(page, "Проверка установки Навигатора", 24);
        title.setTypeface(null, Typeface.BOLD);
        text(page, "Выбери скачанный APK Навигатора из пары 2.8.3. Проверка сравнит файл "
                + "с установленным приложением. Затем можно повторить обновление с обычным "
                + "подтверждением Android и сохранить подробный результат.", 17);
        select = button(page, "Выбрать APK Навигатора"); select.setOnClickListener(v -> selectApk());
        status = text(page, "", 20); status.setTextIsSelectable(true);
        install = button(page, "Установить и получить результат");
        install.setOnClickListener(v -> installOrContinue());
        cancel = button(page, "Отменить попытку установки");
        cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Отменить текущую попытку обновления?")
                .setNegativeButton("Продолжить ожидание", null)
                .setPositiveButton("Отменить попытку", (dialog, which) -> NavigatorInstallDiagnostics.cancel(this))
                .show());
        button(page, "Сохранить / отправить отчёт").setOnClickListener(v -> share());
        button(page, "Копировать отчёт").setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Natro: установка Навигатора",
                    NavigatorInstallDiagnostics.summary(this) + "\n\n" + NavigatorInstallDiagnostics.report(this)));
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
        });
        text(page, "Технические сведения", 19);
        details = text(page, "", 15); details.setTypeface(Typeface.MONOSPACE); details.setTextIsSelectable(true);
        setContentView(scroll);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, scroll);
        NavigatorInstallDiagnostics.receive(this, getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); NavigatorInstallDiagnostics.receive(this, intent);
    }
    @Override protected void onResume() {
        super.onResume(); NavigatorInstallDiagnostics.reconcile(this);
        main.removeCallbacks(refresh); main.post(refresh);
    }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    private void refreshScreen() {
        String phase = NavigatorInstallDiagnostics.phase(this);
        String summary = NavigatorInstallDiagnostics.summary(this), report = NavigatorInstallDiagnostics.report(this);
        if (!summary.equals(displayedSummary)) { status.setText(summary); displayedSummary = summary; }
        if (!report.equals(displayedReport)) { details.setText(report); displayedReport = report; }
        boolean busy = NavigatorInstallDiagnostics.busy();
        select.setEnabled(!busy && NavigatorInstallPolicy.canSelect(phase));
        install.setVisibility(NavigatorInstallPolicy.READY.equals(phase)
                || NavigatorInstallPolicy.USER_ACTION.equals(phase) ? View.VISIBLE : View.GONE);
        install.setEnabled(!busy);
        install.setText(NavigatorInstallPolicy.USER_ACTION.equals(phase)
                ? "Подтвердить установку в Android" : "Установить и получить результат");
        cancel.setVisibility(NavigatorInstallPolicy.pending(phase) ? View.VISIBLE : View.GONE);
        cancel.setEnabled(!busy);
    }
    private void selectApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, SELECT_APK); }
        catch (RuntimeException e) {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), SELECT_APK); }
            catch (RuntimeException unavailable) { Toast.makeText(this, "На магнитоле не найдено окно выбора файлов", Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SELECT_APK && result == RESULT_OK && data != null && data.getData() != null) {
            NavigatorInstallDiagnostics.prepare(this, data.getData());
        }
        // Returning from permission/confirmation UI never means installation succeeded.
    }
    private void installOrContinue() {
        if (NavigatorInstallPolicy.USER_ACTION.equals(NavigatorInstallDiagnostics.phase(this))) {
            NavigatorInstallDiagnostics.continueConfirmation(this, (intent, error) -> {
                if (intent == null) return;
                if (isFinishing() || isDestroyed()) {
                    NavigatorInstallDiagnostics.confirmationLaunchFailed(this, new IllegalStateException("Diagnostic screen is not active")); return;
                }
                try { startActivity(intent); }
                catch (RuntimeException e) { NavigatorInstallDiagnostics.confirmationLaunchFailed(this, e); }
            });
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION); }
            catch (RuntimeException e) { Toast.makeText(this, "Разреши установку из Natro в настройках Android", Toast.LENGTH_LONG).show(); }
            return;
        }
        NavigatorInstallDiagnostics.install(this);
    }
    private void share() {
        NavigatorInstallDiagnostics.export(this, file -> {
            if (isFinishing() || isDestroyed()) return;
            if (file == null) { Toast.makeText(this, "Не удалось сохранить отчёт", Toast.LENGTH_LONG).show(); return; }
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).setClipData(ClipData.newRawUri("Отчёт установки", uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, "Сохранить или отправить отчёт"));
            } catch (RuntimeException e) { Toast.makeText(this, "Отчёт сохранён; можно скопировать его кнопкой выше", Toast.LENGTH_LONG).show(); }
        });
    }
    private TextView text(LinearLayout page, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(10), 0, dp(10)); page.addView(view); return view;
    }
    private Button button(LinearLayout page, String value) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false);
        page.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
