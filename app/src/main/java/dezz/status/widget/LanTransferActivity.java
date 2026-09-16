/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.adb.AdbRemoteInbox;
import dezz.status.widget.settings.SettingsBackNavigation;
import dezz.status.widget.transfer.LanTransferService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Pairing is visible only on the head unit. Server and remote shell permissions are separate. */
public final class LanTransferActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private TextView status, address, code;
    private Button toggle;
    private Switch commands;
    private EditText text;
    private boolean binding;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            LanTransferService service = LanTransferService.instance;
            binding = true; toggle.setText(service == null ? "Включить сервер" : "Выключить сервер");
            commands.setEnabled(service != null); commands.setChecked(service != null && service.commandsAllowed()); binding = false;
            status.setText(service == null ? "Сервер выключен" : service.status());
            code.setText(service == null ? "" : "Код: " + service.pin() + "  ·  " + service.pinRemainingSeconds() + " с");
            main.postDelayed(this, 1000);
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 16, 24, 24);
        root.addView(button("← Назад", this::finish)); root.addView(label("Буфер обмена и файлы · iPhone", 24));
        root.addView(label("Магнитола — сервер. Откройте показанный адрес в Safari и введите код. Подключите магнитолу к режиму модема iPhone или оба устройства к одной Wi-Fi-сети.", 18));
        status = label("Сервер выключен", 18); root.addView(status);
        address = label("Поиск локальных адресов…", 20); address.setTextIsSelectable(true); root.addView(address);
        code = label("", 24); root.addView(code);
        LinearLayout actions = new LinearLayout(this);
        toggle = button("Включить сервер", () -> {
            if (LanTransferService.instance == null) startForegroundService(new Intent(this, LanTransferService.class));
            else stopService(new Intent(this, LanTransferService.class));
        }); actions.addView(toggle);
        actions.addView(button("Новый код / отозвать доступ", () -> { if (LanTransferService.instance != null) LanTransferService.instance.rotate(); }));
        actions.addView(button("Обновить адреса", this::addresses)); root.addView(actions);
        commands = new Switch(this); commands.setText("Разрешить удалённое выполнение ADB-команд, включая root");
        commands.setOnCheckedChangeListener((view, value) -> { if (!binding && LanTransferService.instance != null) LanTransferService.instance.allowCommands(value); }); root.addView(commands);
        root.addView(label("Используйте только доверенную сеть: локальный HTTP не шифруется. Сервер не доступен через облако. После смены сети обновите адрес. Код действует 5 минут, сопряжение — 30 минут бездействия. Отключение сервера отзывает доступ.", 15));
        text = new EditText(this); text.setHint("Текст или команда — ничего не запускается при вставке"); text.setMinLines(3); text.setMaxLines(8); root.addView(text);
        LinearLayout clipboard = new LinearLayout(this);
        clipboard.addView(button("Из буфера Android", () -> {
            ClipboardManager manager = getSystemService(ClipboardManager.class); ClipData data = manager.getPrimaryClip();
            if (data != null && data.getItemCount() > 0 && data.getItemAt(0).getText() != null) text.setText(data.getItemAt(0).getText());
        }));
        clipboard.addView(button("Из клиента iPhone", () -> { if (LanTransferService.instance != null) text.setText(LanTransferService.instance.text()); }));
        clipboard.addView(button("В буфер / для iPhone", () -> {
            try {
                if (LanTransferService.instance != null) LanTransferService.instance.setText(text.getText().toString());
                else getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Natro", text.getText()));
            } catch (RuntimeException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        clipboard.addView(button("В терминал ADB", () -> { AdbRemoteInbox.draft(text.getText().toString()); startActivity(new Intent(this, AdbSettingsActivity.class)); })); root.addView(clipboard);
        root.addView(button("Папка полученных файлов", () -> {
            LanTransferService service = LanTransferService.instance;
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Полученные файлы")
                    .setMessage(service == null ? "Включите сервер, чтобы увидеть папку приёма" : service.folder() + "\n\nФайлы также доступны в списке Safari. APK автоматически не устанавливаются.")
                    .setPositiveButton("Закрыть", null).show();
        }));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll); SettingsBackNavigation.applySafeTopInset(this, root);
        if (saved != null) text.setText(saved.getString("text", "")); addresses();
    }
    private void addresses() { network.execute(() -> {
        String value = String.join("\n", LanTransferService.addresses());
        main.post(() -> { if (!isDestroyed()) address.setText(value.isEmpty() ? "Нет локального IPv4. Проверьте подключение Wi-Fi / модема iPhone." : value); });
    }); }
    @Override protected void onResume() { super.onResume(); main.post(refresh); addresses(); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { network.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("text", text.getText().toString()); }
    private Button button(String title, Runnable action) { Button result = new Button(this); result.setText(title); result.setAllCaps(false); result.setOnClickListener(v -> action.run()); return result; }
    private TextView label(String title, int size) { TextView result = new TextView(this); result.setText(title); result.setTextSize(size); return result; }
}
