/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.climate.ScreenReservationStateStore;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.launcher.LauncherSafeAreaPolicy;
import dezz.status.widget.launcher.information.InformationPanelConfigStore;
import dezz.status.widget.mqtt.MqttController;
import dezz.status.widget.settings.SettingsDestinationCatalog;
import dezz.status.widget.settings.SettingsDestinationCatalog.Destination;
import dezz.status.widget.settings.SettingsDestinationCatalog.Group;
import dezz.status.widget.settings.SettingsResponsiveLayoutPolicy;
import dezz.status.widget.sprut.SprutHubController;

/**
 * Canonical settings entry point.
 *
 * <p>On the wide landscape display used by the head unit this deliberately follows the iPad
 * Settings pattern: one stable sidebar, search, grouped rows and a detail list on the right.  On
 * a narrower display the exact same catalog collapses into horizontally scrollable sections.
 * Existing detail editors remain responsible for their preference values, so installing this
 * update does not migrate or reset a single user setting.</p>
 */
public final class SettingsHubActivity extends AppCompatActivity {
    public static final String EXTRA_GROUP = "dezz.status.widget.extra.SETTINGS_GROUP";
    public static final String EXTRA_SHOW_BACK = "dezz.status.widget.extra.SETTINGS_SHOW_BACK";
    private static final String TAG = "SettingsHub";
    private static final String EXPORT_FILE_PREFIX = "status-widget-settings-";
    private static final String EXPORT_MIME = "application/json";

    private static final class CategoryViews {
        @NonNull final LinearLayout row;
        @NonNull final TextView title;
        @NonNull final TextView subtitle;
        @NonNull final ImageView icon;
        @NonNull final FrameLayout iconBackground;

        CategoryViews(@NonNull LinearLayout row, @NonNull TextView title,
                      @NonNull TextView subtitle, @NonNull ImageView icon,
                      @NonNull FrameLayout iconBackground) {
            this.row = row;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.iconBackground = iconBackground;
        }
    }

    private Preferences preferences;
    private LinearLayout root;
    private LinearLayout content;
    private EditText search;
    private MaterialButton hubBackButton;
    @Nullable private AlertDialog permissionsDialog;
    private Group selectedGroup = Group.STATUS;
    private boolean splitPane;
    private boolean showBack;
    private boolean hasResumed;
    private final Map<Group, CategoryViews> categoryViews = new EnumMap<>(Group.class);
    private final List<MaterialButton> compactTabs = new ArrayList<>();
    private final Runnable safeInsetRefresh = new Runnable() {
        @Override public void run() {
            if (root == null || !root.isAttachedToWindow()) return;
            ViewCompat.requestApplyInsets(root);
            root.postDelayed(this, 750L);
        }
    };

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) importSettings(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        selectedGroup = Group.fromId(getIntent().getStringExtra(EXTRA_GROUP));
        showBack = getIntent().getBooleanExtra(EXTRA_SHOW_BACK, false);
        splitPane = SettingsResponsiveLayoutPolicy.useSplitPane(
                getResources().getConfiguration().screenWidthDp);
        setContentView(buildScreen());
        applySafeInsets();
        renderNavigationSelection();
        renderContent();
        updateBackVisibility();
        AppRuntimeBootstrap.run(this, preferences);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasResumed) {
            // A system permission screen does not recreate this Activity. Reconcile enabled
            // services explicitly so a manually granted overlay/location permission takes effect
            // immediately when the user returns.
            AppRuntimeBootstrap.reconcileServices(this, preferences);
        } else {
            hasResumed = true;
        }
        if (content != null) renderContent();
        if (root != null) {
            root.removeCallbacks(safeInsetRefresh);
            root.post(safeInsetRefresh);
        }
        if (permissionsDialog != null && permissionsDialog.isShowing()) showPermissions();
    }

    @Override
    protected void onPause() {
        if (root != null) root.removeCallbacks(safeInsetRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (root != null) root.removeCallbacks(safeInsetRefresh);
        AlertDialog dialog = permissionsDialog;
        permissionsDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectedGroup = Group.fromId(intent.getStringExtra(EXTRA_GROUP));
        showBack = intent.getBooleanExtra(EXTRA_SHOW_BACK, false);
        if (search != null && search.length() > 0) search.setText("");
        renderNavigationSelection();
        renderContent();
        updateBackVisibility();
    }

    @NonNull
    public static Intent intent(@NonNull android.content.Context context, @NonNull Group group) {
        return new Intent(context, SettingsHubActivity.class)
                .putExtra(EXTRA_GROUP, group.id)
                .putExtra(EXTRA_SHOW_BACK, true);
    }

    @NonNull
    private View buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(splitPane ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.settings_background));

        if (splitPane) {
            int sidebarWidth = SettingsResponsiveLayoutPolicy.sidebarWidthDp(
                    getResources().getConfiguration().screenWidthDp);
            root.addView(buildSidebar(), new LinearLayout.LayoutParams(dp(sidebarWidth), match()));
            root.addView(buildContentPane(), new LinearLayout.LayoutParams(0, match(), 1f));
        } else {
            root.addView(buildCompactHeader(), new LinearLayout.LayoutParams(match(), wrap()));
            root.addView(buildContentPane(), new LinearLayout.LayoutParams(match(), 0, 1f));
        }
        return root;
    }

    @NonNull
    private View buildSidebar() {
        LinearLayout sidebar = column();
        sidebar.setPadding(dp(20), dp(18), dp(16), dp(18));
        sidebar.setBackgroundColor(color(R.color.settings_sidebar_background));

        hubBackButton = buildBackButton();
        sidebar.addView(hubBackButton, new LinearLayout.LayoutParams(dp(124), dp(46)));
        TextView title = text("Настройки", 32, Typeface.BOLD);
        sidebar.addView(title, topMargin(4));
        TextView version = secondary("Status Widget · " + VersionGetter.getAppVersionName(this), 13);
        sidebar.addView(version, topMargin(2));
        search = searchField();
        sidebar.addView(search, topMargin(16));

        ScrollView categoryScroll = new ScrollView(this);
        categoryScroll.setFillViewport(true);
        LinearLayout categories = column();
        categories.setPadding(0, dp(12), 0, dp(12));
        categoryScroll.addView(categories, new ScrollView.LayoutParams(match(), wrap()));
        for (Group group : Group.values()) categories.addView(categoryRow(group), topMargin(4));
        sidebar.addView(categoryScroll, new LinearLayout.LayoutParams(match(), 0, 1f));

        TextView autosave = secondary("Изменения сохраняются автоматически", 12);
        autosave.setGravity(Gravity.CENTER);
        sidebar.addView(autosave, new LinearLayout.LayoutParams(match(), dp(34)));
        return sidebar;
    }

    @NonNull
    private View buildCompactHeader() {
        LinearLayout header = column();
        header.setPadding(dp(18), dp(14), dp(18), dp(8));
        hubBackButton = buildBackButton();
        header.addView(hubBackButton, new LinearLayout.LayoutParams(dp(124), dp(46)));
        TextView title = text("Настройки", 30, Typeface.BOLD);
        header.addView(title, topMargin(3));
        search = searchField();
        header.addView(search, topMargin(10));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.setPadding(0, dp(8), 0, dp(4));
        for (Group group : Group.values()) {
            MaterialButton tab = new MaterialButton(this);
            tab.setAllCaps(false);
            tab.setText(group.title);
            tab.setTag(group);
            tab.setMinHeight(dp(44));
            tab.setInsetTop(0);
            tab.setInsetBottom(0);
            tab.setOnClickListener(v -> selectGroup((Group) v.getTag()));
            compactTabs.add(tab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap(), dp(44));
            lp.rightMargin = dp(7);
            tabs.addView(tab, lp);
        }
        horizontal.addView(tabs, new HorizontalScrollView.LayoutParams(wrap(), match()));
        header.addView(horizontal, matchWrap());
        return header;
    }

    @NonNull
    private View buildContentPane() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = column();
        content.setPadding(dp(splitPane ? 34 : 18), dp(22),
                dp(splitPane ? 34 : 18), dp(44));
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));
        return scroll;
    }

    @NonNull
    private EditText searchField() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Поиск настроек");
        field.setTextSize(16);
        field.setCompoundDrawablePadding(dp(8));
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(color(R.color.settings_search_background), 12));
        Drawable icon = tintedDrawable(R.drawable.ic_settings,
                color(R.color.settings_secondary_text));
        if (icon != null) {
            icon.setBounds(0, 0, dp(19), dp(19));
            field.setCompoundDrawables(icon, null, null, null);
        }
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (content != null) renderContent();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return field;
    }

    @NonNull
    private View categoryRow(@NonNull Group group) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(7), dp(8), dp(7));
        row.setMinimumHeight(dp(62));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> selectGroup(group));

        FrameLayout iconBackground = new FrameLayout(this);
        iconBackground.setBackground(rounded(groupColor(group), 9));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedDrawable(groupIcon(group), Color.WHITE));
        icon.setContentDescription(null);
        iconBackground.addView(icon, centeredFrame(dp(21), dp(21)));
        row.addView(iconBackground, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout labels = column();
        labels.setPadding(dp(11), 0, 0, 0);
        TextView title = text(group.title, 16, Typeface.BOLD);
        TextView subtitle = secondary(group.subtitle, 13);
        subtitle.setMaxLines(1);
        labels.addView(title, matchWrap());
        labels.addView(subtitle, matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0, wrap(), 1f));

        CategoryViews views = new CategoryViews(row, title, subtitle, icon, iconBackground);
        categoryViews.put(group, views);
        return row;
    }

    private void selectGroup(@NonNull Group group) {
        selectedGroup = group;
        if (search != null && search.length() > 0) search.setText("");
        hideKeyboard();
        renderNavigationSelection();
        renderContent();
    }

    private void renderNavigationSelection() {
        for (Map.Entry<Group, CategoryViews> entry : categoryViews.entrySet()) {
            boolean selected = entry.getKey() == selectedGroup;
            CategoryViews views = entry.getValue();
            views.row.setSelected(selected);
            views.row.setContentDescription(entry.getKey().title
                    + (selected ? ". Выбрано" : ""));
            views.row.setBackground(selected
                    ? rounded(color(R.color.settings_accent), 12)
                    : rounded(Color.TRANSPARENT, 12));
            views.title.setTextColor(selected ? Color.WHITE : themeTextColor());
            views.subtitle.setTextColor(selected ? 0xCCFFFFFF
                    : color(R.color.settings_secondary_text));
            views.iconBackground.setBackground(rounded(selected ? 0x30FFFFFF
                    : groupColor(entry.getKey()), 9));
        }
        for (MaterialButton tab : compactTabs) {
            Group group = (Group) tab.getTag();
            boolean selected = group == selectedGroup;
            tab.setSelected(selected);
            tab.setContentDescription(group.title + (selected ? ". Выбрано" : ""));
            tab.setTextColor(selected ? Color.WHITE : themeTextColor());
            tab.setBackgroundTintList(ColorStateList.valueOf(selected
                    ? color(R.color.settings_accent)
                    : color(R.color.settings_group_background)));
        }
    }

    private void renderContent() {
        if (content == null) return;
        content.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim();
        if (!query.isEmpty()) {
            content.addView(text("Результаты поиска", 30, Typeface.BOLD), matchWrap());
            List<Destination> matches = SettingsDestinationCatalog.search(query);
            content.addView(secondary(matches.isEmpty()
                    ? "Ничего не найдено. Попробуйте название функции или устройства."
                    : "Найдено: " + matches.size(), 15), topMargin(4));
            if (matches.isEmpty()) {
                content.addView(emptyCard(), topMargin(18));
            } else {
                content.addView(destinationCard(matches, true), topMargin(18));
            }
            return;
        }

        content.addView(text(selectedGroup.title, 31, Typeface.BOLD), matchWrap());
        content.addView(secondary(selectedGroup.subtitle, 15), topMargin(4));
        List<Destination> destinations = SettingsDestinationCatalog.forGroup(selectedGroup);
        content.addView(destinationCard(destinations, false), topMargin(20));

        TextView hint = secondary(sectionFooter(selectedGroup), 13);
        hint.setPadding(dp(8), dp(12), dp(8), 0);
        content.addView(hint, matchWrap());
    }

    @NonNull
    private View destinationCard(@NonNull List<Destination> destinations, boolean showGroup) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setCardBackgroundColor(color(R.color.settings_group_background));
        card.setStrokeWidth(0);
        LinearLayout rows = column();
        for (int index = 0; index < destinations.size(); index++) {
            Destination destination = destinations.get(index);
            rows.addView(destinationRow(destination, showGroup), new LinearLayout.LayoutParams(
                    match(), wrap()));
            if (index + 1 < destinations.size()) rows.addView(separator(), separatorLp());
        }
        card.addView(rows, new MaterialCardView.LayoutParams(match(), wrap()));
        return card;
    }

    @NonNull
    private View destinationRow(@NonNull Destination destination, boolean showGroup) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(13), dp(10));
        row.setMinimumHeight(dp(72));
        row.setBackgroundResource(R.drawable.settings_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> open(destination));

        FrameLayout iconBackground = new FrameLayout(this);
        iconBackground.setBackground(rounded(destinationColor(destination), 10));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedDrawable(destinationIcon(destination.icon), Color.WHITE));
        icon.setContentDescription(null);
        iconBackground.addView(icon, centeredFrame(dp(22), dp(22)));
        row.addView(iconBackground, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = column();
        labels.setPadding(dp(13), 0, dp(8), 0);
        TextView title = text(destination.title, 17,
                destination.action != null
                        && SettingsDestinationCatalog.ACTION_RESET.equals(destination.action)
                        ? Typeface.BOLD : Typeface.NORMAL);
        if (SettingsDestinationCatalog.ACTION_RESET.equals(destination.action)) {
            title.setTextColor(color(R.color.settings_destructive));
        }
        TextView subtitle = secondary(showGroup
                ? destination.group.title + " · " + destination.subtitle
                : destination.subtitle, 13);
        subtitle.setMaxLines(2);
        labels.addView(title, matchWrap());
        labels.addView(subtitle, topMargin(2));
        row.addView(labels, new LinearLayout.LayoutParams(0, wrap(), 1f));

        LinearLayout accessory = row();
        accessory.setGravity(Gravity.CENTER_VERTICAL);
        String summary = summary(destination.id);
        if (!summary.isEmpty()) {
            TextView value = secondary(summary, 13);
            value.setGravity(Gravity.END);
            value.setMaxLines(2);
            accessory.addView(value, new LinearLayout.LayoutParams(wrap(), wrap()));
        }
        TextView chevron = secondary("›", 30);
        chevron.setGravity(Gravity.CENTER);
        chevron.setContentDescription(null);
        accessory.addView(chevron, new LinearLayout.LayoutParams(dp(26), dp(42)));
        row.addView(accessory, new LinearLayout.LayoutParams(wrap(), wrap()));
        row.setContentDescription(destination.title + ". " + destination.subtitle
                + (summary.isEmpty() ? "" : ". " + summary));
        return row;
    }

    @NonNull
    private View emptyCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setCardBackgroundColor(color(R.color.settings_group_background));
        TextView text = secondary("Проверьте написание или выберите раздел слева.", 15);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(28), dp(24), dp(28));
        card.addView(text);
        return card;
    }

    private void open(@NonNull Destination destination) {
        if (destination.activityClassName != null) {
            try {
                Class<?> activity = Class.forName(destination.activityClassName);
                startActivity(new Intent(this, activity));
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e(TAG, "Cannot open " + destination.activityClassName, error);
                Toast.makeText(this, "Не удалось открыть раздел", Toast.LENGTH_LONG).show();
            }
            return;
        }
        String action = destination.action;
        if (SettingsDestinationCatalog.ACTION_EDIT_HOME_LAYOUT.equals(action)) {
            startActivity(new Intent(this, LauncherActivity.class)
                    .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true));
        } else if (SettingsDestinationCatalog.ACTION_PERMISSIONS.equals(action)) {
            showPermissions();
        } else if (SettingsDestinationCatalog.ACTION_EXPORT.equals(action)) {
            exportSettings();
        } else if (SettingsDestinationCatalog.ACTION_IMPORT.equals(action)) {
            importLauncher.launch(new String[]{EXPORT_MIME, "*/*"});
        } else if (SettingsDestinationCatalog.ACTION_RESET.equals(action)) {
            confirmReset();
        }
    }

    private void showPermissions() {
        AlertDialog previous = permissionsDialog;
        permissionsDialog = null;
        if (previous != null && previous.isShowing()) previous.dismiss();

        LinearLayout page = column();
        page.setPadding(dp(20), dp(4), dp(20), 0);
        TextView explanation = secondary(
                "Зелёная отметка означает, что функция уже доступна. Нажмите на строку, "
                        + "чтобы открыть нужный системный экран.", 14);
        page.addView(explanation, bottomMargin(10));

        addPermissionButton(page, "Отображение поверх приложений",
                Permissions.checkOverlayPermission(this),
                () -> safeStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        addPermissionButton(page, "Доступ к уведомлениям",
                Permissions.isNotificationAccessGranted(this),
                () -> safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addPermissionButton(page, "Статистика использования приложений",
                Permissions.isUsageAccessGranted(this),
                () -> {
                    if (!SettingsLauncher.openUsageAccessSettings(this)) {
                        Toast.makeText(this, R.string.system_settings_not_available,
                                Toast.LENGTH_LONG).show();
                    }
                });
        boolean location = Permissions.checkForMissingForegroundPermissions(this).isEmpty()
                && Permissions.isBackgroundLocationGranted(this);
        addPermissionButton(page, "Местоположение, включая фон", location,
                () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        String component = new ComponentName(this, WidgetAccessibilityService.class)
                .flattenToString();
        addPermissionButton(page, "Служба специальных возможностей",
                Permissions.isAccessibilityServiceEnabled(this, component),
                () -> safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Доступы приложения")
                .setView(scroll)
                .setNegativeButton("Готово", (ignored, which) -> renderContent())
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (permissionsDialog == dialog) permissionsDialog = null;
        });
        permissionsDialog = dialog;
        dialog.show();
    }

    private void addPermissionButton(@NonNull LinearLayout parent, @NonNull String title,
                                     boolean granted, @NonNull Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setText((granted ? "✓  " : "○  ") + title);
        button.setTextColor(granted ? color(R.color.settings_success) : themeTextColor());
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(54));
        lp.topMargin = dp(4);
        parent.addView(button, lp);
    }

    private void exportSettings() {
        try {
            String json = preferences.exportToJson();
            File exports = new File(getCacheDir(), "exports");
            if (!exports.exists() && !exports.mkdirs()) throw new IOException("mkdir");
            String fileName = EXPORT_FILE_PREFIX
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".json";
            File file = new File(exports, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(EXPORT_MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, fileName)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.export_chooser_title)));
        } catch (Exception error) {
            Log.e(TAG, "Export failed", error);
            Toast.makeText(this, R.string.export_failed_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings(@NonNull Uri uri) {
        StringBuilder json = new StringBuilder();
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("open");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line).append('\n');
            }
            preferences.importFromJson(json.toString());
        } catch (Preferences.InvalidSettingsFileException invalid) {
            Log.w(TAG, "Invalid settings file", invalid);
            Toast.makeText(this, R.string.import_invalid_file_toast, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception error) {
            Log.e(TAG, "Import failed", error);
            Toast.makeText(this, R.string.import_failed_toast, Toast.LENGTH_LONG).show();
            return;
        }

        if (WidgetService.isRunning()) stopService(new Intent(this, WidgetService.class));
        if (preferences.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startForegroundService(new Intent(this, WidgetService.class));
        }
        ClimatePanelService.apply(this);
        Toast.makeText(this, R.string.import_success_toast, Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_settings_title)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_settings_confirm,
                        (dialog, which) -> resetAllSettings())
                .show();
    }

    private void resetAllSettings() {
        if (WidgetService.isRunning()) stopService(new Intent(this, WidgetService.class));
        if (preferences.climatePanelEnabled.get()
                || new ScreenReservationStateStore(this).hasManagedReservation()) {
            ClimatePanelService.stopAndRestore(this);
        }
        preferences.resetAll();
        new AutomationStateStore(this).clearAll();
        Intent restart = new Intent(this, SettingsHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(restart);
        finish();
    }

    @NonNull
    private String summary(@NonNull String id) {
        switch (id) {
            case "status_widget":
                return preferences.widgetEnabled.get() ? "Включена" : "Выключена";
            case "status_smart_elements":
                return countLabel(jsonCount(preferences.haMainBricksJson.get()), "элемент");
            case "status_presets":
                return "Профили";
            case "home_behavior":
                return isDefaultHome() ? "Выбран как HOME" : "Другой HOME";
            case "home_layout":
                return "Визуально";
            case "home_panel_content":
                return visiblePanelCount() + " пан.";
            case "panel_apps":
                return countLabel(new FavoriteAppsConfigStore(preferences).load().size(),
                        "приложение");
            case "panel_media":
                return enabledLabel(preferences.launcherMediaVisible.get());
            case "panel_navigation":
            case "panel_routes":
                return enabledLabel(preferences.launcherNavigationVisible.get()
                        || preferences.launcherFavoriteRoutesVisible.get());
            case "panel_climate":
                return enabledLabel(preferences.launcherClimateVisible.get()
                        || preferences.climatePanelEnabled.get());
            case "panel_vehicle":
                return enabledLabel(preferences.launcherVehicleInfoVisible.get());
            case "panel_information":
                if (!preferences.launcherInformationVisible.get()) return "Выключена";
                return new InformationPanelConfigStore(preferences).load().hasEnabledItems()
                        ? "Включена" : "Нет статусов";
            case "panel_actions":
                return actionsPanelSummary(preferences.launcherActionsVisible.get(),
                        preferences.launcherShortcutsJson.get());
            case "panel_popup":
                return enabledLabel(preferences.popupEnabled.get());
            case "connector_ha": {
                HaApiController controller = HaApiController.active();
                return exactConnectorLabel(preferences.haApiEnabled.get(),
                        controller != null && controller.isOnline());
            }
            case "connector_sprut":
                return exactConnectorLabel(preferences.sprutEnabled.get(),
                        SprutHubController.isSynced());
            case "connector_mqtt":
                return preferences.mqttEnabled.get()
                        ? (MqttController.isConnected() ? "Подключено" : "Включено")
                        : "Выключено";
            case "connector_phone": {
                if (!preferences.phoneConnectorEnabled.get()) return "Выключено";
                String address = preferences.phoneDeviceAddress.get().trim();
                return address.isEmpty() ? "Выберите iPhone"
                        : "Включено · " + PhoneConnectorSettingsActivity.maskedAddress(address);
            }
            case "automation_visual":
                return countLabel(jsonCount(preferences.localScenariosJson.get()), "сценарий");
            case "automation_intent":
                return countLabel(jsonCount(preferences.intentActionRulesJson.get()), "команда");
            case "app_permissions":
                int missing = missingPermissionCount();
                return missing == 0 ? "Всё настроено" : "Не настроено: " + missing;
            case "app_about":
                return VersionGetter.getAppVersionName(this);
            default:
                return "";
        }
    }

    private int visiblePanelCount() {
        int count = 0;
        if (preferences.launcherAppsVisible.get()) count++;
        if (preferences.launcherMediaVisible.get()) count++;
        if (preferences.launcherClockVisible.get()) count++;
        if (preferences.launcherNavigationVisible.get()
                || preferences.launcherFavoriteRoutesVisible.get()) count++;
        if (preferences.launcherActionsVisible.get()) count++;
        if (preferences.launcherClimateVisible.get()) count++;
        if (preferences.launcherVehicleInfoVisible.get()) count++;
        if (preferences.launcherInformationVisible.get()
                && new InformationPanelConfigStore(preferences).load().hasEnabledItems()) {
            count++;
        }
        return count;
    }

    /**
     * Keeps the actions card honest about both visibility layers: the outer HOME panel can be
     * hidden while all of its saved shortcuts remain intact, and individual shortcuts can be
     * disabled without being deleted.
     */
    @NonNull
    static String actionsPanelSummary(boolean panelVisible, @Nullable String raw) {
        if (!panelVisible) return "Скрыта";
        int enabled = 0;
        int total = 0;
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray items = new JSONObject(raw).optJSONArray("items");
                if (items != null) {
                    for (int index = 0; index < items.length(); index++) {
                        JSONObject item = items.optJSONObject(index);
                        if (item == null) continue;
                        total++;
                        if (item.optBoolean("enabled", true)) enabled++;
                    }
                }
            } catch (Exception ignored) {
                // A broken/imported document is reported as an empty set; the shortcut store owns
                // recovery and must remain the only component allowed to change persisted data.
            }
        }
        return "Включено: " + enabled + " из " + total;
    }

    private int missingPermissionCount() {
        int count = 0;
        if (!Permissions.checkOverlayPermission(this)) count++;
        if (!Permissions.isNotificationAccessGranted(this)) count++;
        if (!Permissions.isUsageAccessGranted(this)) count++;
        if (!Permissions.checkForMissingForegroundPermissions(this).isEmpty()
                || !Permissions.isBackgroundLocationGranted(this)) count++;
        String component = new ComponentName(this, WidgetAccessibilityService.class)
                .flattenToString();
        if (!Permissions.isAccessibilityServiceEnabled(this, component)) count++;
        return count;
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo result = getPackageManager().resolveActivity(
                    home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return result != null && result.activityInfo != null
                    && getPackageName().equals(result.activityInfo.packageName);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int jsonCount(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        try {
            String value = raw.trim();
            if (value.startsWith("[")) return new JSONArray(value).length();
            JSONObject object = new JSONObject(value);
            String[] arrays = {"items", "rules", "routes", "overlays", "scenarios"};
            for (String key : arrays) {
                JSONArray array = object.optJSONArray(key);
                if (array != null) return array.length();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    @NonNull
    private static String countLabel(int count, @NonNull String singular) {
        if (count == 0) return "Не настроено";
        if ("элемент".equals(singular)) return count + " элем.";
        if ("приложение".equals(singular)) return count + " прил.";
        if ("сценарий".equals(singular)) return count + " сцен.";
        if ("команда".equals(singular)) return count + " ком.";
        return count + " шт.";
    }

    @NonNull
    private static String enabledLabel(boolean enabled) {
        return enabled ? "Показывается" : "Скрыта";
    }

    @NonNull
    private static String exactConnectorLabel(boolean enabled, boolean connected) {
        if (!enabled) return "Выключено";
        return connected ? "Подключено" : "Включено";
    }

    @NonNull
    private static String sectionFooter(@NonNull Group group) {
        switch (group) {
            case STATUS:
                return "Внешний вид каждого элемента настраивается визуально. "
                        + "Старые значения и порядок полностью сохраняются.";
            case HOME:
                return "Размер и положение панелей меняются на самом HOME, поэтому "
                        + "результат редактора совпадает с реальным экраном.";
            case PANELS:
                return "Для медиа, навигации, климата и данных автомобиля доступны "
                        + "собственные визуальные редакторы.";
            case SMART_HOME:
                return "После настройки подключения устройства доступны в тех панелях "
                        + "и сценариях, которые поддерживают выбранный коннектор.";
            case AUTOMATION:
                return "Сценарии используют подтверждённые состояния коннекторов. "
                        + "Секретные команды и ключи остаются только на устройстве.";
            case APP:
            default:
                return "Экспорт включает интерфейс, HOME, панели и обычные сценарии. "
                        + "Пароли, токены и секретные команды не покидают устройство.";
        }
    }

    private void applySafeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            WidgetService service = WidgetService.getInstance();
            boolean running = service != null && WidgetService.isRunning();
            int widgetHeight = running ? service.getStatusBarOverlayHeight() : 0;
            int top = LauncherSafeAreaPolicy.topInset(bars.top,
                    preferences.widgetEnabled.get(), preferences.widgetMode.get() == 1,
                    running, widgetHeight);
            view.setPadding(bars.left, top, bars.right, bars.bottom);
            return insets;
        });
        root.post(() -> ViewCompat.requestApplyInsets(root));
    }

    @NonNull
    private MaterialButton buildBackButton() {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText("Назад");
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setIconResource(R.drawable.ic_arrow_back);
        button.setIconTint(ColorStateList.valueOf(color(R.color.settings_accent)));
        button.setTextColor(color(R.color.settings_accent));
        button.setBackgroundTintList(ColorStateList.valueOf(
                color(R.color.settings_group_background)));
        button.setStrokeColor(ColorStateList.valueOf(color(R.color.settings_separator)));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(14));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setContentDescription("Назад");
        button.setOnClickListener(view -> finish());
        button.setVisibility(showBack ? View.VISIBLE : View.GONE);
        return button;
    }

    private void updateBackVisibility() {
        if (hubBackButton != null) {
            hubBackButton.setVisibility(showBack ? View.VISIBLE : View.GONE);
        }
    }

    private void safeStart(@NonNull Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.system_settings_not_available,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    @DrawableRes
    private int groupIcon(@NonNull Group group) {
        switch (group) {
            case STATUS: return R.drawable.ic_section_widget;
            case HOME: return R.drawable.ic_launcher_home;
            case PANELS: return R.drawable.ic_section_content;
            case SMART_HOME: return R.drawable.ic_smart_plug;
            case AUTOMATION: return R.drawable.ic_smart_alarm;
            case APP:
            default: return R.drawable.ic_settings;
        }
    }

    @DrawableRes
    private int destinationIcon(@NonNull String key) {
        switch (key) {
            case "status": return R.drawable.ic_section_widget;
            case "smart_home": return R.drawable.ic_smart_plug;
            case "preset": return R.drawable.ic_preset;
            case "home": return R.drawable.ic_launcher_home;
            case "layout": return R.drawable.ic_section_sizes;
            case "panels": return R.drawable.ic_section_content;
            case "apps": return R.drawable.ic_launcher_apps;
            case "media": return R.drawable.ic_smart_music;
            case "navigation": return R.drawable.ic_launcher_navigation;
            case "routes": return R.drawable.ic_launcher_work;
            case "climate": return R.drawable.ic_car_climate;
            case "vehicle": return R.drawable.ic_smart_car;
            case "information": return R.drawable.ic_info;
            case "actions": return R.drawable.ic_popup_power;
            case "popup": return R.drawable.ic_popup_light;
            case "ha": return R.drawable.ic_smart_location;
            case "sprut": return R.drawable.ic_smart_motion;
            case "mqtt": return R.drawable.ic_status_wifi_internet;
            case "phone": return R.drawable.ic_smart_phone;
            case "scenario": return R.drawable.ic_smart_alarm;
            case "intent": return R.drawable.ic_car_wheel_heat;
            case "permissions": return R.drawable.ic_popup_lock;
            case "export": return R.drawable.ic_export;
            case "import": return R.drawable.ic_import;
            case "about": return R.drawable.ic_info;
            case "reset": return R.drawable.ic_reset;
            default: return R.drawable.ic_settings;
        }
    }

    @ColorInt
    private int groupColor(@NonNull Group group) {
        switch (group) {
            case STATUS: return color(R.color.settings_accent);
            case HOME: return color(R.color.settings_indigo);
            case PANELS: return color(R.color.settings_warning);
            case SMART_HOME: return color(R.color.settings_success);
            case AUTOMATION: return color(R.color.settings_purple);
            case APP:
            default: return 0xFF8E8E93;
        }
    }

    @ColorInt
    private int destinationColor(@NonNull Destination destination) {
        if (SettingsDestinationCatalog.ACTION_RESET.equals(destination.action)) {
            return color(R.color.settings_destructive);
        }
        return groupColor(destination.group);
    }

    @Nullable
    private Drawable tintedDrawable(@DrawableRes int id, @ColorInt int tint) {
        Drawable source = ContextCompat.getDrawable(this, id);
        if (source == null) return null;
        Drawable drawable = DrawableCompat.wrap(source.mutate());
        DrawableCompat.setTint(drawable, tint);
        return drawable;
    }

    @NonNull
    private GradientDrawable rounded(@ColorInt int fill, int radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fill);
        value.setCornerRadius(dp(radiusDp));
        return value;
    }

    @NonNull
    private View separator() {
        View value = new View(this);
        value.setBackgroundColor(color(R.color.settings_separator));
        return value;
    }

    @NonNull
    private LinearLayout.LayoutParams separatorLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(1));
        lp.leftMargin = dp(67);
        return lp;
    }

    @NonNull
    private TextView text(@NonNull String value, float size, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(themeTextColor());
        view.setTypeface(view.getTypeface(), style);
        return view;
    }

    @NonNull
    private TextView secondary(@NonNull String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(R.color.settings_secondary_text));
        return view;
    }

    @NonNull private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    @NonNull private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        return value;
    }

    @NonNull
    private FrameLayout.LayoutParams centeredFrame(int width, int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    @NonNull
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    @NonNull
    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = this.dp(dp);
        return lp;
    }

    @NonNull
    private LinearLayout.LayoutParams bottomMargin(int dp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = this.dp(dp);
        return lp;
    }

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    private int themeTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
                && value.resourceId != 0) {
            return ContextCompat.getColor(this, value.resourceId);
        }
        return isNight() ? Color.WHITE : 0xFF1C1C1E;
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }
}
