/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.launcher.MediaPlaybackTargetPolicy;
import dezz.status.widget.launcher.media.MediaPanelConfig;
import dezz.status.widget.launcher.media.MediaPanelConfigStore;
import dezz.status.widget.launcher.media.MediaPanelView;
import dezz.status.widget.launcher.MediaPlaybackHistoryStore;
import dezz.status.widget.launcher.panels.PanelEditScheduler;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Visual, immediate editor for every element inside the HOME media panel. */
public final class MediaPanelSettingsActivity extends AppCompatActivity {
    private interface IntChange { void set(int value); }
    private interface ElementIntChange { int set(int value); }
    private interface ColorChange { void set(@NonNull String value); }
    private interface ColorValue { String get(); }
    private interface ValueLabel { String format(int value); }

    private Preferences preferences;
    private MediaPanelConfigStore store;
    private MediaPanelConfig config;
    private MediaPanelView preview;
    private LinearLayout elementList;
    private PanelEditScheduler editScheduler;
    private boolean resumedOnce;
    private final Map<String, TextView> positionLabels = new LinkedHashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new MediaPanelConfigStore(preferences);
        config = store.load();
        editScheduler = PanelEditScheduler.onMainThread(this::updatePreviewNow, this::saveNow);
        setTitle("Медиаблок");
        installContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) {
            // The real-HOME editor writes through the same store while this Activity is stopped.
            // Reload before rebuilding controls so a later onStop cannot overwrite those edits
            // with the stale in-memory object that existed before LauncherActivity was opened.
            config = store.load();
            installContent();
            updatePreviewNow();
        }
        resumedOnce = true;
    }

    private void installContent() {
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
    }

    @NonNull
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), 0, dp(22), dp(28));
        settingsScroll.addView(settings, new ScrollView.LayoutParams(match(), wrap()));

        addTitle(settings, "Медиаблок");
        addHint(settings, "Расположение и размер элементов можно менять и здесь, в живом "
                + "предпросмотре справа, и прямо в фактическом блоке HOME. Точные ползунки, "
                + "состав элементов, оформление и прокрутка текста остаются синхронизированы.");
        MaterialSwitch panelVisible = new MaterialSwitch(this);
        panelVisible.setText("Показывать медиаблок на HOME");
        panelVisible.setTextSize(16);
        panelVisible.setMinHeight(dp(50));
        panelVisible.setChecked(preferences.launcherMediaVisible.get());
        panelVisible.setOnCheckedChangeListener((button, checked) ->
                preferences.launcherMediaVisible.set(checked));
        settings.addView(panelVisible, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(settings, "Размер и положение блока на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));
        addButton(settings, "Расположение элементов внутри блока на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MEDIA_CONTENT, true)));

        addTitle(settings, "После загрузки магнитолы");
        MediaPlaybackHistoryStore.Snapshot playbackHistory =
                MediaPlaybackHistoryStore.read(this);
        String fixedPackage = preferences.launcherMediaFixedPlayerPackage.get().trim();
        MaterialSwitch fixedPlayer = new MaterialSwitch(this);
        fixedPlayer.setText("Всегда управлять выбранным плеером");
        fixedPlayer.setTextSize(16);
        fixedPlayer.setMinHeight(dp(50));
        fixedPlayer.setChecked(preferences.launcherMediaFixedPlayerEnabled.get()
                && !fixedPackage.isEmpty());
        fixedPlayer.setOnCheckedChangeListener((button, checked) -> {
            String selected = preferences.launcherMediaFixedPlayerPackage.get().trim();
            if (checked && selected.isEmpty()) {
                preferences.launcherMediaFixedPlayerEnabled.set(false);
                button.setChecked(false);
                showMediaPlayerPicker();
                return;
            }
            preferences.launcherMediaFixedPlayerEnabled.set(checked);
        });
        settings.addView(fixedPlayer, new LinearLayout.LayoutParams(match(), wrap()));
        String selectedPlayer = fixedPackage.isEmpty()
                ? "не выбран"
                : applicationLabel(fixedPackage) + " · " + fixedPackage;
        addButton(settings, "Выбрать плеер · " + selectedPlayer,
                view -> showMediaPlayerPicker());
        String effectiveTarget = MediaPlaybackTargetPolicy.resolve(
                preferences.launcherMediaFixedPlayerEnabled.get(),
                fixedPackage, playbackHistory.packageName);
        addHint(settings, "Все кнопки — включая ручной PLAY — отправляются только пакету "
                + (effectiveTarget.isEmpty()
                ? "последнего обнаруженного Android-плеера"
                : applicationLabel(effectiveTarget))
                + ". Общая Bluetooth-команда больше не используется.");

        MaterialSwitch autoResume = new MaterialSwitch(this);
        autoResume.setText("Автоматически продолжать музыку после загрузки");
        autoResume.setTextSize(16);
        autoResume.setMinHeight(dp(50));
        autoResume.setChecked(preferences.launcherMediaAutoResumeEnabled.get());
        autoResume.setOnCheckedChangeListener((button, checked) ->
                preferences.launcherMediaAutoResumeEnabled.set(checked));
        settings.addView(autoResume, new LinearLayout.LayoutParams(match(), wrap()));
        String remembered = playbackHistory.packageName.isEmpty()
                ? "пока не определён"
                : applicationLabel(playbackHistory.packageName) + " · "
                + (playbackHistory.wasPlaying ? "играл" : "был на паузе");
        addHint(settings, "Последний плеер: " + remembered + ". В обычном режиме после "
                + "загрузки PLAY отправляется ему, только если он играл перед выключением. "
                + "В режиме фиксированного плеера PLAY всегда адресуется выбранному приложению. "
                + "Тот же трек и очередь восстанавливает сам плеер.");
        addSlider(settings, "Задержка команды",
                Math.max(0, Math.min(60,
                        preferences.launcherMediaAutoResumeDelaySeconds.get())),
                0, 60,
                preferences.launcherMediaAutoResumeDelaySeconds::set,
                value -> value + " с");

        addTitle(settings, "Сетка");
        addHint(settings, "Число столбцов и строк задаёт точность свободных фреймов. "
                + "Элементы можно накладывать друг на друга: занятость сетки больше не "
                + "ограничивает перемещение и масштабирование.");
        addGridSlider(settings, "Столбцы", config.gridColumns,
                MediaPanelConfig.MIN_GRID_COLUMNS, MediaPanelConfig.MAX_GRID_COLUMNS,
                selected -> {
                    boolean changed = config.setGridSize(selected, config.gridRows);
                    if (changed) rebuildElementControls();
                    return config.gridColumns;
                });
        addGridSlider(settings, "Строки", config.gridRows,
                MediaPanelConfig.MIN_GRID_ROWS, MediaPanelConfig.MAX_GRID_ROWS,
                selected -> {
                    boolean changed = config.setGridSize(config.gridColumns, selected);
                    if (changed) rebuildElementControls();
                    return config.gridRows;
                });

        addTitle(settings, "Элементы");
        addHint(settings, "Перетаскивайте элементы в предпросмотре справа; потяните за любой "
                + "из четырёх углов, чтобы изменить размер. Эти ползунки задают точные значения в "
                + "ячейках, а масштаб меняет только текст или значок.");
        elementList = new LinearLayout(this);
        elementList.setOrientation(LinearLayout.VERTICAL);
        settings.addView(elementList, new LinearLayout.LayoutParams(match(), wrap()));
        rebuildElementControls();

        addTitle(settings, "Геометрия и цвета");
        addHint(settings, "У медиавиджетов нет автоматических подложек и рамок. При "
                + "необходимости добавьте отдельную подложку в режиме компоновки HOME.");
        addSlider(settings, "Расстояние между элементами", config.spacingPx, 0, 48,
                value -> config.spacingPx = value, value -> value + " px");
        addSlider(settings, "Внутренний отступ", config.contentPaddingPx, 0, 64,
                value -> config.contentPaddingPx = value, value -> value + " px");
        addColor(settings, "Название", () -> config.titleColor,
                value -> config.titleColor = value);
        addColor(settings, "Исполнитель, альбом, время и приложение",
                () -> config.secondaryColor,
                value -> config.secondaryColor = value);
        addColor(settings, "Кнопки", () -> config.controlColor,
                value -> config.controlColor = value);
        addColor(settings, "Акцент активной кнопки", () -> config.accentColor,
                value -> config.accentColor = value);

        addButton(settings, "Вернуть медиаблок по умолчанию", v ->
                new AlertDialog.Builder(this)
                        .setTitle("Сбросить медиаблок?")
                        .setMessage("Состав, порядок, размеры и оформление вернутся к исходным. Положение блока на HOME сохранится.")
                        .setPositiveButton("Сбросить", (dialog, which) -> {
                            store.reset();
                            config = store.load();
                            recreate();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(10), 0, 0, 0);
        TextView previewTitle = new TextView(this);
        previewTitle.setText("ЖИВОЙ РЕДАКТОР · ТАЩИТЕ ЭЛЕМЕНТЫ");
        previewTitle.setTextSize(14);
        previewTitle.setTextColor(Color.rgb(105, 165, 255));
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));

        FrameLayout previewHost = new FrameLayout(this);
        GradientDrawable hostBackground = new GradientDrawable();
        hostBackground.setColor(Color.rgb(8, 12, 18));
        hostBackground.setCornerRadius(dp(22));
        previewHost.setBackground(hostBackground);
        previewHost.setPadding(dp(14), dp(14), dp(14), dp(14));
        preview = new MediaPanelView(this, store, null);
        preview.setConfig(config);
        preview.setPreviewContent("Очень длинное название композиции для проверки прокрутки",
                "Исполнитель с длинным именем", "Яндекс Музыка", true);
        preview.setInPlaceEditMode(true, (updated, movedId, finished) -> {
            config = updated.copy();
            for (String id : positionLabels.keySet()) updatePositionLabel(id);
            if (!finished) return;
            store.save(config);
            rebuildElementControls();
        });
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        TextView saved = new TextView(this);
        saved.setText("✓ Изменения сохраняются автоматически");
        saved.setTextSize(13);
        saved.setGravity(Gravity.CENTER);
        saved.setAlpha(.72f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(settingsScroll, new LinearLayout.LayoutParams(0, match(), .50f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .50f));
        return root;
    }

    private void rebuildElementControls() {
        if (elementList == null) return;
        elementList.removeAllViews();
        positionLabels.clear();
        for (MediaPanelConfig.Element element : config.orderedElements()) {
            MediaPanelConfig.Spec spec = MediaPanelConfig.spec(element.id);
            if (spec == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(4), dp(8), dp(7));

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            MaterialSwitch enabled = new MaterialSwitch(this);
            enabled.setText(spec.label);
            enabled.setTextSize(16);
            enabled.setChecked(element.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                boolean actual = config.setEnabled(element.id, checked);
                if (actual != checked) {
                    button.setChecked(actual);
                    Toast.makeText(this, "На сетке нет свободного места для элемента",
                            Toast.LENGTH_SHORT).show();
                }
                persistAndPreview();
            });
            heading.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1f));
            MaterialButton up = compactButton("↑");
            MaterialButton down = compactButton("↓");
            up.setContentDescription("Поднять " + spec.label);
            down.setContentDescription("Опустить " + spec.label);
            up.setOnClickListener(v -> moveElement(element.id, -1));
            down.setOnClickListener(v -> moveElement(element.id, 1));
            heading.addView(up, new LinearLayout.LayoutParams(dp(54), dp(44)));
            heading.addView(down, new LinearLayout.LayoutParams(dp(54), dp(44)));
            card.addView(heading, new LinearLayout.LayoutParams(match(), wrap()));

            TextView position = new TextView(this);
            position.setTextSize(13);
            position.setAlpha(.75f);
            position.setPadding(dp(6), 0, dp(6), 0);
            positionLabels.put(element.id, position);
            card.addView(position, new LinearLayout.LayoutParams(match(), dp(27)));
            updatePositionLabel(element.id);

            addElementSlider(card, "Ширина", element.columnSpan, 1,
                    config.gridColumns, selected -> {
                        MediaPanelConfig.Element current = config.element(element.id);
                        config.setSpan(element.id, selected, current.rowSpan);
                        return config.element(element.id).columnSpan;
                    }, selected -> selected + " яч.");
            addElementSlider(card, "Высота", element.rowSpan, 1,
                    config.gridRows, selected -> {
                        MediaPanelConfig.Element current = config.element(element.id);
                        config.setSpan(element.id, current.columnSpan, selected);
                        return config.element(element.id).rowSpan;
                    }, selected -> selected + " яч.");
            addElementSlider(card, "Содержимое", element.scalePercent, 45, 220,
                    selected -> {
                        config.setScale(element.id, selected);
                        return config.element(element.id).scalePercent;
                    },
                    selected -> selected + "%");
            if (MediaPanelConfig.ARTWORK.equals(element.id)) {
                addElementSlider(card, "Скругление обложки", config.artworkCornerRadiusPx,
                        0, 128, selected -> {
                            config.setArtworkCornerRadiusPx(selected);
                            return config.artworkCornerRadiusPx;
                        }, selected -> selected + " px");
            }
            if (MediaPanelConfig.PROGRESS.equals(element.id)) {
                addElementSlider(card, "Толщина полосы", element.progressBarHeightDp, 2, 40,
                        selected -> {
                            config.setProgressBarHeightDp(selected);
                            return config.element(MediaPanelConfig.PROGRESS).progressBarHeightDp;
                        },
                        selected -> selected + " dp");
                addElementSlider(card, "Отступ времени от полосы", element.progressTimeGapDp,
                        0, 48, selected -> {
                            config.setProgressTimeGapDp(selected);
                            return config.element(MediaPanelConfig.PROGRESS).progressTimeGapDp;
                        }, selected -> selected + " dp");
            }
            if (MediaPanelConfig.VOLUME.equals(element.id)) {
                MaterialSwitch thumbVisible = new MaterialSwitch(this);
                thumbVisible.setText("Показывать кружок громкости");
                thumbVisible.setTextSize(13);
                thumbVisible.setMinHeight(dp(42));
                thumbVisible.setChecked(element.volumeThumbVisible);
                thumbVisible.setOnCheckedChangeListener((button, checked) -> {
                    config.setVolumeThumbVisible(checked);
                    persistAndPreview();
                });
                card.addView(thumbVisible, new LinearLayout.LayoutParams(match(), dp(42)));
                addElementSlider(card, "Размер кружка", element.volumeThumbSizePercent,
                        25, 220, selected -> {
                            config.setVolumeThumbSizePercent(selected);
                            return config.element(MediaPanelConfig.VOLUME)
                                    .volumeThumbSizePercent;
                        }, selected -> selected + "%");
            }
            if (MediaPanelConfig.supportsMarquee(element.id)) {
                MaterialSwitch marquee = new MaterialSwitch(this);
                marquee.setText("Прокручивать длинный текст");
                marquee.setTextSize(13);
                marquee.setMinHeight(dp(42));
                marquee.setChecked(element.marqueeEnabled);
                marquee.setOnCheckedChangeListener((button, checked) -> {
                    config.setMarqueeEnabled(element.id, checked);
                    persistAndPreview();
                });
                card.addView(marquee, new LinearLayout.LayoutParams(match(), dp(42)));
            }

            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(35, 120, 170, 230));
            background.setCornerRadius(dp(10));
            card.setBackground(background);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(match(), wrap());
            cardLp.topMargin = dp(6);
            elementList.addView(card, cardLp);
        }
    }

    private void moveElement(@NonNull String id, int direction) {
        config.move(id, direction);
        persistAndPreview();
        rebuildElementControls();
    }

    private void persistAndPreview() {
        config.normalize();
        for (String id : positionLabels.keySet()) updatePositionLabel(id);
        editScheduler.request();
    }

    private void updatePreviewNow() {
        config.normalize();
        if (preview == null) return;
        preview.setConfig(config);
        preview.setPreviewContent("Очень длинное название композиции для проверки прокрутки",
                "Исполнитель с длинным именем", "Яндекс Музыка", true);
    }

    private void saveNow() {
        config.normalize();
        store.save(config);
    }

    private void addElementSlider(@NonNull LinearLayout parent, @NonNull String title,
                                  int initial, int minimum, int maximum,
                                  @NonNull ElementIntChange change,
                                  @NonNull ValueLabel formatter) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(13);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        TextView value = new TextView(this);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setText(formatter.format(initial));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = minimum + progress;
                value.setText(formatter.format(selected));
                if (!user) return;
                int actual = change.set(selected);
                persistAndPreview();
                if (actual != selected) {
                    seek.setProgress(Math.max(0, Math.min(maximum - minimum, actual - minimum)));
                    value.setText(formatter.format(actual) + " · занято");
                } else {
                    value.setText(formatter.format(actual));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                editScheduler.flush();
            }
        });
        row.addView(label, new LinearLayout.LayoutParams(dp(96), dp(38)));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(38), 1f));
        row.addView(value, new LinearLayout.LayoutParams(dp(72), dp(38)));
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(38)));
    }

    private void updatePositionLabel(@NonNull String id) {
        TextView label = positionLabels.get(id);
        if (label == null) return;
        MediaPanelConfig.Element element = config.element(id);
        label.setText("Позиция: столбец " + (element.column + 1) + "/" + config.gridColumns
                + ", строка " + (element.row + 1) + "/" + config.gridRows + " · "
                + element.columnSpan + "×" + element.rowSpan);
    }

    private void addGridSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                               int minimum, int maximum, @NonNull ElementIntChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(16);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        TextView value = new TextView(this);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setText(String.valueOf(initial));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = minimum + progress;
                value.setText(String.valueOf(selected));
                if (!user) return;
                int actual = change.set(selected);
                persistAndPreview();
                if (actual != selected) {
                    seek.setProgress(actual - minimum);
                    value.setText(actual + " · предел");
                } else {
                    value.setText(String.valueOf(actual));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                editScheduler.flush();
            }
        });
        row.addView(label, new LinearLayout.LayoutParams(dp(110), dp(44)));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row.addView(value, new LinearLayout.LayoutParams(dp(118), dp(44)));
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(44)));
    }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                           int minimum, int maximum, @NonNull IntChange listener,
                           @NonNull ValueLabel formatter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(16);
        TextView value = new TextView(this);
        value.setGravity(Gravity.END);
        value.setText(formatter.format(initial));
        heading.addView(label, new LinearLayout.LayoutParams(0, wrap(), 1f));
        heading.addView(value, new LinearLayout.LayoutParams(dp(104), wrap()));
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = progress + minimum;
                value.setText(formatter.format(selected));
                if (user) {
                    listener.set(selected);
                    persistAndPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                editScheduler.flush();
            }
        });
        block.addView(heading);
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(38)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(7);
        parent.addView(block, lp);
    }

    private void addColor(@NonNull LinearLayout parent, @NonNull String title,
                          @NonNull ColorValue current, @NonNull ColorChange listener) {
        MaterialButton button = new MaterialButton(this);
        AppleColorPickerDialog.decorateButton(button, title, current.get());
        button.setOnClickListener(v -> {
            String original = current.get();
            AppleColorPickerDialog.show(this, title, original,
                    AppleColorPickerDialog.Options.standard(),
                    new AppleColorPickerDialog.Listener() {
                        private void apply(@Nullable String selected) {
                            String value = selected == null ? original : selected;
                            listener.set(value);
                            persistAndPreview();
                            AppleColorPickerDialog.decorateButton(button, title, value);
                        }

                        @Override public void onPreview(@Nullable String selected) {
                            apply(selected);
                        }

                        @Override public void onSelected(@Nullable String selected) {
                            apply(selected);
                        }
                    });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(62));
        lp.topMargin = dp(6);
        parent.addView(button, lp);
    }

    private void showMediaPlayerPicker() {
        LinkedHashMap<String, LauncherAppCatalog.App> byPackage = new LinkedHashMap<>();
        for (LauncherAppCatalog.App app : LauncherAppCatalog.loadIncludingSystem(this)) {
            if (getPackageName().equals(app.packageName)) continue;
            byPackage.putIfAbsent(app.packageName, app);
        }
        List<LauncherAppCatalog.App> choices = new ArrayList<>(byPackage.values());
        if (choices.isEmpty()) {
            Toast.makeText(this, "Не найдено приложений для выбора",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        String current = preferences.launcherMediaFixedPlayerPackage.get().trim();
        int checked = -1;
        for (int index = 0; index < choices.size(); index++) {
            LauncherAppCatalog.App app = choices.get(index);
            labels[index] = app.label + "\n" + app.packageName;
            if (current.equals(app.packageName)) checked = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Музыкальный плеер")
                .setSingleChoiceItems(labels, checked, null)
                .setNeutralButton("Последний плеер", (value, which) -> {
                    preferences.launcherMediaFixedPlayerEnabled.set(false);
                    preferences.launcherMediaFixedPlayerPackage.set("");
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                    LauncherAppCatalog.App app = choices.get(position);
                    preferences.launcherMediaFixedPlayerPackage.set(app.packageName);
                    preferences.launcherMediaFixedPlayerEnabled.set(true);
                    dialog.dismiss();
                    recreate();
                }));
        dialog.show();
    }

    @NonNull
    private String applicationLabel(@NonNull String packageName) {
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    @NonNull
    private MaterialButton compactButton(@NonNull String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(20);
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void addButton(@NonNull LinearLayout parent, @NonNull String label,
                           @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(52));
        lp.topMargin = dp(7);
        parent.addView(button, lp);
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(23);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        parent.addView(title, lp);
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(15);
        hint.setAlpha(.78f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(8);
        parent.addView(hint, lp);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStop() {
        // Also persists the last drag if the system stops the Activity before ACTION_UP.
        editScheduler.flush();
        saveNow();
        super.onStop();
    }

    @Override protected void onDestroy() {
        editScheduler.cancel();
        super.onDestroy();
    }
}
