/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dezz.status.widget.phone.PhoneAppIconStore;
import dezz.status.widget.phone.PhoneIconImporter;

/** User mappings and local PNG/JPEG replacement without changing ANCS identities. */
public final class PhoneAppIconsActivity extends dezz.status.widget.settings.SettingsActivity {
    private static final int PICK_ICON = 71;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PhoneAppIconStore store;
    private final List<PhoneAppIconStore.App> apps = new ArrayList<>(), visible = new ArrayList<>();
    private String pendingId = "", pendingName = "";
    private EditText search;
    private TextView summary;
    private IconAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { pendingId = state.getString("pending_id", ""); pendingName = state.getString("pending_name", ""); }
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹ Назад"); back.setOnClickListener(v -> finish()); header.addView(back);
        TextView title = label("Иконки приложений телефона", 24);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("Добавить приложение"); add.setOnClickListener(v -> addApplication()); header.addView(add);
        page.addView(header);
        page.addView(label("Нажмите приложение, чтобы выбрать свой PNG/JPEG или вернуть автоматическую иконку. Здесь показаны приложения, от которых уже приходили уведомления, и добавленные вручную.", 18));
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(20);
        search.setHint("Поиск по названию или ID приложения"); page.addView(search);
        summary = label("Загрузка каталога…", 18); page.addView(summary);
        Button refresh = button("Скачать недостающие и обновить список");
        refresh.setOnClickListener(v -> worker.execute(() -> { store.retryMissingIcons(); reload(); }));
        page.addView(refresh);
        ListView list = new ListView(this); adapter = new IconAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> choose(visible.get(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, page);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable s) {}
        });
        if (state != null) search.setText(state.getString("search", ""));
        refresh.setEnabled(false);
        worker.execute(() -> {
            store = PhoneAppIconStore.get(getApplicationContext());
            runOnUiThread(() -> { if (!isDestroyed()) refresh.setEnabled(true); });
            reload();
        });
    }

    private void reload() {
        try {
            // Do not hide a broken user catalog behind the best-effort automatic catalog.
            store.overrides().entries();
            List<PhoneAppIconStore.App> snapshot = store.catalog();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                apps.clear(); apps.addAll(snapshot); filter();
            });
        } catch (Exception error) { report(error); }
    }
    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (PhoneAppIconStore.App app : apps) if ((app.name + " " + app.identifier).toLowerCase(Locale.ROOT).contains(query)) visible.add(app);
        summary.setText("Приложений: " + apps.size() + " · показано: " + visible.size());
        adapter.notifyDataSetChanged();
    }
    private void choose(PhoneAppIconStore.App app) {
        String[] actions = app.customIcon ? new String[]{"Выбрать PNG/JPEG", "Вернуть автоматическую иконку"}
                : new String[]{"Выбрать PNG/JPEG"};
        new AlertDialog.Builder(this).setTitle(app.name).setItems(actions, (dialog, which) -> {
            if (which == 0) pick(app.identifier, app.name);
            else worker.execute(() -> {
                try { store.overrides().reset(app.identifier); store.requestAutomaticIcon(app.identifier, app.name); reload(); }
                catch (Exception error) { report(error); }
            });
        }).setNegativeButton("Отмена", null).show();
    }
    private void addApplication() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText name = new EditText(this); name.setHint("Название приложения"); name.setSingleLine(true);
        EditText id = new EditText(this); id.setHint("ID приложения iPhone, например org.telegram.telegram"); id.setSingleLine(true);
        form.addView(name); form.addView(id);
        form.addView(label("Для приложения из списка ID уже известен. При ручном добавлении он должен точно совпадать с ID в уведомлении.", 18));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Добавить иконку приложения")
                .setView(form).setPositiveButton("Выбрать изображение", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = id.getText().toString().trim().toLowerCase(Locale.ROOT);
            String title = name.getText().toString().trim();
            if (!key.matches("[a-z0-9][a-z0-9._-]{0,255}")) { id.setError("Укажите корректный ID приложения"); return; }
            if (title.isEmpty() || title.length() > 256) { name.setError("Введите название до 256 символов"); return; }
            dialog.dismiss(); pick(key, title);
        }));
        dialog.show();
    }
    private void pick(String id, String name) {
        pendingId = id; pendingName = name;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg"});
        try { startActivityForResult(intent, PICK_ICON); }
        catch (android.content.ActivityNotFoundException unavailable) { report(new Exception("На устройстве нет выбора файлов")); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ICON || result != RESULT_OK || data == null || data.getData() == null || pendingId.isEmpty()) return;
        final String id = pendingId, name = pendingName;
        final android.net.Uri uri = data.getData(); pendingId = ""; pendingName = "";
        worker.execute(() -> {
            try {
                byte[] png = PhoneIconImporter.read(getApplicationContext(), uri);
                store.overrides().put(id, name, png);
                reload();
                runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) Toast.makeText(this, "Иконка сохранена. Будет использована в следующих уведомлениях.", Toast.LENGTH_LONG).show(); });
            } catch (Exception | OutOfMemoryError error) { report(error); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_id", pendingId); state.putString("pending_name", pendingName);
        state.putString("search", search.getText().toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    private void report(Throwable error) { runOnUiThread(() -> {
        if (!isDestroyed() && !isFinishing()) Toast.makeText(this,
                error.getMessage() == null ? "Не удалось сохранить иконку" : error.getMessage(), Toast.LENGTH_LONG).show();
    }); }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); return view; }
    private Button button(String text) { Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(18); return button; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class IconAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return visible.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            PhoneAppIconStore.App app = visible.get(position);
            LinearLayout row = new LinearLayout(PhoneAppIconsActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            ImageView icon = new ImageView(PhoneAppIconsActivity.this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
            String status = app.customIcon ? (app.customIconAvailable ? "Своя иконка" : "Своя иконка недоступна")
                    : (app.iconCached ? "Автоматическая иконка" : "Иконка не найдена — можно добавить свою");
            TextView text = label(app.name + "\n" + app.identifier + "\n" + status, 19);
            text.setPadding(dp(16), 0, 0, 0); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            worker.execute(() -> {
                Drawable image = store.drawable(app.identifier);
                runOnUiThread(() -> { if (!isDestroyed() && image != null) icon.setImageDrawable(image); });
            });
            return row;
        }
    }
}
