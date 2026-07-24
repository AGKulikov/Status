/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.apps.FavoriteAppConfig;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.launcher.panels.PanelEditScheduler;

/** Code-free, autosaving editor for applications shown in the HOME favourites panel. */
public final class FavoriteAppsSettingsActivity extends AppCompatActivity {
    private interface IntChange { void set(int value); }

    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable changedNotifier = () -> {
        FavoriteAppsSettingsActivity.this.changeNotificationPending = false;
        sendBroadcast(new Intent(FavoriteAppsConfigStore.ACTION_CHANGED)
                .setPackage(getPackageName()));
    };

    private Preferences preferences;
    private FavoriteAppsConfigStore store;
    private LinearLayout itemsHost;
    private MaterialButton addButton;
    private TextView catalogStatus;
    private TextView savedStatus;
    private final List<AppChoice> catalog = new ArrayList<>();
    private boolean catalogLoaded;
    private boolean destroyed;
    private boolean changeNotificationPending;
    @Nullable private PanelEditScheduler appearanceEditScheduler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new FavoriteAppsConfigStore(preferences);
        setTitle("Избранные приложения HOME");
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.install(this, screen);
        refreshRows();
        loadCatalog();
    }

    @Override
    protected void onStop() {
        if (appearanceEditScheduler != null) appearanceEditScheduler.flush();
        flushChangeNotification();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (appearanceEditScheduler != null) appearanceEditScheduler.cancel();
        mainHandler.removeCallbacks(changedNotifier);
        catalogExecutor.shutdownNow();
        super.onDestroy();
    }

    @NonNull
    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(34));
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));

        MaterialButton back = new MaterialButton(this);
        back.setText("←  Назад");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(190), dp(50));
        backLp.bottomMargin = dp(8);
        content.addView(back, backLp);

        TextView title = text("Избранные приложения", 26, true);
        content.addView(title);
        TextView hint = text("Добавляйте приложения из установленного списка, меняйте их порядок и настраивайте каждую иконку отдельно. Кнопки «Сохранить» нет — изменения применяются автоматически.", 15, false);
        hint.setAlpha(.78f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(match(), wrap());
        hintLp.bottomMargin = dp(10);
        content.addView(hint, hintLp);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        addButton = new MaterialButton(this);
        addButton.setText("+  Добавить приложение");
        addButton.setAllCaps(false);
        addButton.setEnabled(false);
        addButton.setOnClickListener(v -> showApplicationPicker());
        toolbar.addView(addButton, new LinearLayout.LayoutParams(dp(310), dp(54)));
        savedStatus = text("✓ Настройки сохраняются автоматически", 14, false);
        savedStatus.setAlpha(.72f);
        savedStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.addView(savedStatus, new LinearLayout.LayoutParams(0, dp(54), 1f));
        content.addView(toolbar, new LinearLayout.LayoutParams(match(), dp(58)));

        catalogStatus = text("Загружаю список установленных приложений…", 14, false);
        catalogStatus.setAlpha(.7f);
        content.addView(catalogStatus, new LinearLayout.LayoutParams(match(), dp(34)));

        itemsHost = new LinearLayout(this);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(itemsHost, new LinearLayout.LayoutParams(match(), wrap()));
        return scroll;
    }

    private void loadCatalog() {
        catalogExecutor.execute(() -> {
            List<AppChoice> loaded = queryApplications();
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) return;
                catalog.clear();
                catalog.addAll(loaded);
                catalogLoaded = true;
                addButton.setEnabled(true);
                catalogStatus.setText(loaded.isEmpty()
                        ? "Установленные приложения не найдены"
                        : "Доступно приложений: " + loaded.size());
                refreshRows();
            });
        });
    }

    @NonNull
    private List<AppChoice> queryApplications() {
        PackageManager manager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppChoice> result = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        try {
            for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
                if (Thread.currentThread().isInterrupted()) break;
                if (info.activityInfo == null || !packages.add(info.activityInfo.packageName)) {
                    continue;
                }
                String label;
                Drawable icon;
                try { label = String.valueOf(info.loadLabel(manager)); }
                catch (RuntimeException ignored) { label = info.activityInfo.packageName; }
                try { icon = HighResolutionAppIconLoader.load(this, info.activityInfo); }
                catch (RuntimeException ignored) { icon = null; }
                result.add(new AppChoice(label, info.activityInfo.packageName, icon));
            }
        } catch (RuntimeException ignored) {
            // The page remains usable for already selected packages when a vendor PM fails.
        }
        result.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private void refreshRows() {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        List<FavoriteAppConfig> selected = store.load();
        if (selected.isEmpty()) {
            MaterialCardView emptyCard = new MaterialCardView(this);
            emptyCard.setRadius(dp(18));
            TextView empty = text("Избранных приложений пока нет. Нажмите «Добавить приложение».",
                    17, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(24), dp(20), dp(24));
            emptyCard.addView(empty, new MaterialCardView.LayoutParams(match(), wrap()));
            itemsHost.addView(emptyCard, new LinearLayout.LayoutParams(match(), wrap()));
            return;
        }
        Map<String, AppChoice> choices = new HashMap<>();
        for (AppChoice value : catalog) choices.put(value.packageName, value);
        for (int index = 0; index < selected.size(); index++) {
            FavoriteAppConfig value = selected.get(index);
            AppChoice app = choices.get(value.packageName);
            itemsHost.addView(buildRow(value, app, index, selected.size()));
        }
    }

    @NonNull
    private View buildRow(@NonNull FavoriteAppConfig config, @Nullable AppChoice app,
                          int index, int count) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(v -> editAppearance(config.packageName, app));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(8), dp(9));

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        setAppIcon(icon, app);
        int previewIcon = Math.min(config.iconSizePx, dp(70));
        preview.addView(icon, new LinearLayout.LayoutParams(previewIcon, previewIcon));
        if (config.showLabel) {
            TextView label = text(app == null ? config.packageName : app.label,
                    config.labelSizeSp, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            preview.addView(label, new LinearLayout.LayoutParams(match(), dp(27)));
        }
        row.addView(preview, new LinearLayout.LayoutParams(dp(150), dp(96)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(app == null ? "Приложение не установлено" : app.label, 18, true);
        TextView packageLabel = text(config.packageName, 13, false);
        packageLabel.setAlpha(.66f);
        TextView sizes = text("Иконка: " + config.iconSizePx + " px · подпись: "
                + (config.showLabel ? config.labelSizeSp + " sp" : "скрыта"), 13, false);
        sizes.setAlpha(.76f);
        details.addView(name);
        details.addView(packageLabel);
        details.addView(sizes);
        row.addView(details, new LinearLayout.LayoutParams(0, wrap(), 1f));

        MaterialButton up = compactButton("↑");
        up.setEnabled(index > 0);
        up.setOnClickListener(v -> mutate(() -> store.move(config.packageName, -1),
                "Порядок изменён"));
        MaterialButton down = compactButton("↓");
        down.setEnabled(index + 1 < count);
        down.setOnClickListener(v -> mutate(() -> store.move(config.packageName, 1),
                "Порядок изменён"));
        MaterialButton edit = compactButton("⚙");
        edit.setContentDescription("Настроить " + (app == null ? config.packageName : app.label));
        edit.setOnClickListener(v -> editAppearance(config.packageName, app));
        MaterialButton remove = compactButton("×");
        remove.setContentDescription("Убрать из избранного");
        remove.setOnClickListener(v -> confirmRemove(config.packageName, app));
        row.addView(up);
        row.addView(down);
        row.addView(edit);
        row.addView(remove);
        card.addView(row, new MaterialCardView.LayoutParams(match(), wrap()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(9);
        card.setLayoutParams(lp);
        return card;
    }

    private interface Mutation { boolean run(); }

    private void mutate(@NonNull Mutation mutation, @NonNull String status) {
        try {
            if (!mutation.run()) return;
            markSaved(status);
            scheduleChangeNotification();
            refreshRows();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "Не удалось изменить список", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRemove(@NonNull String packageName, @Nullable AppChoice app) {
        String label = app == null ? packageName : app.label;
        new AlertDialog.Builder(this)
                .setTitle("Убрать «" + label + "» из избранного?")
                .setMessage("Оформление запомнится и восстановится, если добавить приложение снова.")
                .setPositiveButton("Убрать", (dialog, which) ->
                        mutate(() -> store.remove(packageName), "Приложение убрано"))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showApplicationPicker() {
        if (!catalogLoaded) {
            Toast.makeText(this, "Список приложений ещё загружается", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> selected = new HashSet<>();
        for (FavoriteAppConfig value : store.load()) selected.add(value.packageName);
        List<AppChoice> available = new ArrayList<>();
        for (AppChoice value : catalog) if (!selected.contains(value.packageName)) {
            available.add(value);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Все доступные приложения уже добавлены", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Поиск приложения");
        body.addView(search, new LinearLayout.LayoutParams(match(), dp(54)));
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        AppPickerAdapter adapter = new AppPickerAdapter(available);
        grid.setAdapter(adapter);
        body.addView(grid, new LinearLayout.LayoutParams(match(), dp(430)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Добавить приложение")
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppChoice value = adapter.getItem(position);
            if (store.add(value.packageName)) {
                markSaved("Добавлено: " + value.label);
                scheduleChangeNotification();
                refreshRows();
            }
            dialog.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(String.valueOf(s));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        dialog.show();
    }

    private void editAppearance(@NonNull String packageName, @Nullable AppChoice app) {
        FavoriteAppConfig config = store.appearance(packageName);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(10), dp(18), dp(16));

        MaterialCardView previewCard = new MaterialCardView(this);
        previewCard.setRadius(dp(20));
        previewCard.setCardBackgroundColor(Color.rgb(31, 39, 53));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER);
        ImageView previewIcon = new ImageView(this);
        setAppIcon(previewIcon, app);
        TextView previewLabel = text(app == null ? packageName : app.label,
                config.labelSizeSp, false);
        previewLabel.setGravity(Gravity.CENTER);
        previewLabel.setMaxLines(1);
        preview.addView(previewIcon);
        preview.addView(previewLabel, new LinearLayout.LayoutParams(match(), dp(36)));
        previewCard.addView(preview, new MaterialCardView.LayoutParams(match(), match()));
        body.addView(previewCard, new LinearLayout.LayoutParams(match(), dp(180)));

        TextView saved = text("✓ Все изменения сохраняются сразу", 13, false);
        saved.setGravity(Gravity.END);
        saved.setAlpha(.72f);
        body.addView(saved, new LinearLayout.LayoutParams(match(), dp(34)));

        Runnable updatePreview = () -> {
            previewIcon.setLayoutParams(new LinearLayout.LayoutParams(
                    config.iconSizePx, config.iconSizePx));
            previewLabel.setTextSize(config.labelSizeSp);
            previewLabel.setVisibility(config.showLabel ? View.VISIBLE : View.GONE);
        };
        updatePreview.run();
        PanelEditScheduler dialogScheduler = PanelEditScheduler.onMainThread(updatePreview,
                () -> saveAppearanceNow(config, saved));
        appearanceEditScheduler = dialogScheduler;

        addSlider(body, "Размер иконки", config.iconSizePx,
                FavoriteAppConfig.MIN_ICON_SIZE_PX, FavoriteAppConfig.MAX_ICON_SIZE_PX,
                value -> {
                    config.iconSizePx = value;
                    dialogScheduler.request();
                }, " px");
        addSlider(body, "Размер подписи", config.labelSizeSp,
                FavoriteAppConfig.MIN_LABEL_SIZE_SP, FavoriteAppConfig.MAX_LABEL_SIZE_SP,
                value -> {
                    config.labelSizeSp = value;
                    dialogScheduler.request();
                }, " sp");
        MaterialSwitch showLabel = new MaterialSwitch(this);
        showLabel.setText("Показывать название приложения");
        showLabel.setChecked(config.showLabel);
        showLabel.setMinHeight(dp(52));
        showLabel.setOnCheckedChangeListener((button, checked) -> {
            config.showLabel = checked;
            dialogScheduler.request();
        });
        body.addView(showLabel, new LinearLayout.LayoutParams(match(), wrap()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(match(), wrap()));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(app == null ? packageName : app.label)
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            dialogScheduler.flush();
            dialogScheduler.cancel();
            if (appearanceEditScheduler == dialogScheduler) appearanceEditScheduler = null;
            refreshRows();
        });
        dialog.show();
    }

    private void saveAppearanceNow(@NonNull FavoriteAppConfig config,
                                   @NonNull TextView status) {
        store.updateAppearance(config);
        status.setText("✓ Сохранено автоматически");
        markSaved("Оформление обновлено");
        scheduleChangeNotification();
    }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                           int minimum, int maximum, @NonNull IntChange listener,
                           @NonNull String suffix) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 16, false);
        TextView value = text(initial + suffix, 15, true);
        value.setGravity(Gravity.END);
        heading.addView(name, new LinearLayout.LayoutParams(0, wrap(), 1f));
        heading.addView(value, new LinearLayout.LayoutParams(dp(100), wrap()));
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = progress + minimum;
                value.setText(selected + suffix);
                if (user) listener.set(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(heading);
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(6);
        parent.addView(block, lp);
    }

    private void markSaved(@NonNull String value) {
        if (savedStatus != null) savedStatus.setText("✓ " + value + " · сохранено");
    }

    private void scheduleChangeNotification() {
        changeNotificationPending = true;
        mainHandler.removeCallbacks(changedNotifier);
        mainHandler.postDelayed(changedNotifier, 120L);
    }

    private void flushChangeNotification() {
        if (!changeNotificationPending) return;
        mainHandler.removeCallbacks(changedNotifier);
        changedNotifier.run();
    }

    private void setAppIcon(@NonNull ImageView view, @Nullable AppChoice app) {
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app != null && app.icon != null) view.setImageDrawable(app.icon);
        else view.setImageResource(R.drawable.ic_launcher_apps);
    }

    @NonNull
    private MaterialButton compactButton(@NonNull String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(18);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return button;
    }

    @NonNull
    private TextView text(@NonNull String value, float size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return text;
    }

    private final class AppPickerAdapter extends BaseAdapter {
        private final List<AppChoice> source;
        private final List<AppChoice> values = new ArrayList<>();

        AppPickerAdapter(@NonNull List<AppChoice> source) {
            this.source = new ArrayList<>(source);
            values.addAll(source);
        }

        void filter(@Nullable String query) {
            String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            values.clear();
            for (AppChoice value : source) {
                if (normalized.isEmpty()
                        || value.label.toLowerCase(Locale.ROOT).contains(normalized)
                        || value.packageName.toLowerCase(Locale.ROOT).contains(normalized)) {
                    values.add(value);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return values.size(); }
        @Override public AppChoice getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            LinearLayout cell = reusable instanceof LinearLayout
                    ? (LinearLayout) reusable : new LinearLayout(FavoriteAppsSettingsActivity.this);
            cell.removeAllViews();
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(5), dp(7), dp(5), dp(7));
            AppChoice app = getItem(position);
            ImageView icon = new ImageView(FavoriteAppsSettingsActivity.this);
            setAppIcon(icon, app);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView label = text(app.label, 12, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(match(), dp(40)));
            return cell;
        }
    }

    private static final class AppChoice {
        @NonNull final String label;
        @NonNull final String packageName;
        @Nullable final Drawable icon;

        AppChoice(@NonNull String label, @NonNull String packageName, @Nullable Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
