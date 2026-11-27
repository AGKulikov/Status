package dezz.status.widget.qnx;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dezz.status.widget.adb.AdbConsoleSession;
import java.security.interfaces.RSAPublicKey;

/** Setup and an explicit end-to-end read-free check on the diagnostics screen. */
public final class QnxAccessDialog {
    private QnxAccessDialog() {}
    public static void show(Activity activity) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);
        CheckBox ssh = new CheckBox(activity);
        ssh.setText("Использовать настроенный SSH на приборке");
        form.addView(ssh);
        EditText user = field(activity, form, "Учётная запись SSH на приборке");
        EditText pin = field(activity, form, "Отпечаток ключа приборки (SHA256:…)");
        TextView status = new TextView(activity);
        try {
            QnxSshProfile previous = QnxSshProfile.loadOptional(activity);
            if (previous != null) {
                ssh.setChecked(true);
                user.setText(previous.user);
                pin.setText(previous.pin);
            }
        } catch (Exception invalid) { ssh.setChecked(true); status.setText(invalid.getMessage()); }
        Button copy = button(activity, form, "Скопировать открытый ключ Natro");
        Runnable updateFields = () -> {
            int visibility = ssh.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE;
            user.setVisibility(visibility); pin.setVisibility(visibility); copy.setVisibility(visibility);
        };
        ssh.setOnCheckedChangeListener((button, checked) -> updateFields.run());
        updateFields.run();
        Button test = button(activity, form, "Проверить доступ");
        form.addView(status);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Доступ к QNX")
                .setMessage("По умолчанию используется диагностическая служба QNX на порту 8000 через ADB. Для настроенного SSH укажите учётную запись и доверенный отпечаток ключа.")
                .setView(scroll).setPositiveButton("Сохранить", null).setNegativeButton("Закрыть", null).create();
        AdbConsoleSession session = new AdbConsoleSession(activity);
        dialog.setOnDismissListener(ignored -> session.close());
        dialog.show();
        copy.setOnClickListener(v -> {
            copy.setEnabled(false);
            new Thread(() -> {
                try {
                    String line = QnxSsh.publicLine((RSAPublicKey)QnxSshProfile.clientKey().getPublic());
                    activity.runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        ClipboardManager clipboard = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("Natro QNX SSH", line));
                        copy.setEnabled(true); status.setText("Открытый ключ Natro скопирован.");
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> { if (dialog.isShowing()) {
                        copy.setEnabled(true); status.setText("Не удалось получить ключ Natro из Android Keystore.");
                    }});
                }
            }, "natro-qnx-public-key").start();
        });
        test.setOnClickListener(v -> {
            try {
                QnxSshProfile profile = ssh.isChecked() ? profile(user, pin) : null;
                test.setEnabled(false); status.setText("Проверка выбранного подключения и службы QNX…");
                session.submit(connection -> {
                    try { connection.connect(); connection.verifyQnx(profile); }
                    finally { connection.disconnect(); }
                }, error -> activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    test.setEnabled(true);
                    status.setText(error == null ? "Служба QNX ответила через выбранное подключение." : error.getMessage());
                }));
            } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (ssh.isChecked()) QnxSshProfile.save(activity, profile(user, pin));
                else QnxSshProfile.useDirect(activity);
                dialog.dismiss();
            }
            catch (Exception error) { status.setText(error.getMessage()); }
        });
    }

    private static QnxSshProfile profile(EditText user, EditText pin) {
        return new QnxSshProfile(user.getText().toString().trim(), pin.getText().toString().trim());
    }
    private static EditText field(Activity activity, LinearLayout form, String hint) {
        EditText field = new EditText(activity);
        field.setSingleLine(); field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(field); return field;
    }
    private static Button button(Activity activity, LinearLayout form, String label) {
        Button button = new Button(activity); button.setText(label); form.addView(button); return button;
    }
}
