/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONException;

import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.navigation.NavigationIntegrationConfig;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Human-facing editor for the Navigator window shown on the main KX11 display. */
public final class NavigatorWindowSettingsActivity extends AppCompatActivity {
    private interface IntValue { int get(); }

    private Preferences preferences;
    private NavigationIntegrationConfig navigation;
    private NavigationIntegrationConfig.FloatingWindowProfile window;

    private MaterialSwitch enabled;
    private MaterialSwitch locked;
    private MaterialSwitch aspectLocked;
    private MaterialSwitch rememberGeometry;
    private MaterialSwitch modeButtonVisible;
    private MaterialSwitch dragHandleVisible;
    private MaterialSwitch resizeHandleVisible;
    private MaterialSwitch closeButtonVisible;
    private SliderField left;
    private SliderField top;
    private SliderField width;
    private SliderField height;
    private SliderField cornerRadius;
    private SliderField opacity;
    private SliderField borderWidth;
    private SliderField shadowRadius;
    private String borderColor;
    private String shadowColor;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        preferences = new Preferences(this);
        navigation = NavigationIntegrationConfig.fromJson(
                preferences.navigationIntegrationConfigJson.get());
        window = navigation.mainFloatingWindow;
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
    }

    @NonNull
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(14), dp(22), dp(30));
        page.setBackgroundColor(0xFF0B0D12);
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        page.addView(header(), matchWrap());
        page.addView(hint("Здесь настраивается именно окно Навигатора на основном экране. "
                + "Карта HUD остаётся независимой и меняется в разделе «Отдельный HUD-дисплей»."),
                topMargin(8));

        page.addView(section("Режим окна"), topMargin(18));
        enabled = toggle("Разрешить оконный режим", window.enabled);
        locked = toggle("Зафиксировать окно",
                window.movementLocked && window.resizeLocked);
        aspectLocked = toggle("Сохранять пропорции при изменении размера",
                window.aspectRatioLocked);
        rememberGeometry = toggle("Запоминать положение и размер", window.rememberGeometry);
        page.addView(enabled, topMargin(5));
        page.addView(locked, topMargin(5));
        page.addView(hint("При фиксации одновременно блокируются перемещение и изменение "
                + "размера. Верхняя ручка и правый нижний уголок скрываются автоматически."),
                topMargin(2));
        page.addView(aspectLocked, topMargin(5));
        page.addView(rememberGeometry, topMargin(5));

        page.addView(section("Положение и размер"), topMargin(20));
        left = slider(page, "Отступ слева", window.leftPercent, 0, 100, " %");
        top = slider(page, "Отступ сверху", window.topPercent, 0, 100, " %");
        width = slider(page, "Ширина окна", window.widthPercent, 20, 100, " %");
        height = slider(page, "Высота окна", window.heightPercent, 20, 100, " %");

        page.addView(section("Внешний вид"), topMargin(20));
        cornerRadius = slider(page, "Скругление углов", window.cornerRadiusDp,
                0, 160, " dp");
        page.addView(hint("Значение 0 оставляет прямоугольное окно. Скругление применяется "
                + "к самой карте, а не только к рамке."), topMargin(2));
        opacity = slider(page, "Непрозрачность окна", window.opacityPercent,
                20, 100, " %");
        borderWidth = slider(page, "Толщина рамки", window.borderWidthDp,
                0, 24, " dp");
        borderColor = window.borderColor;
        shadowColor = window.shadowColor;
        page.addView(colorButton("Цвет рамки", borderColor, value -> borderColor = value),
                topMargin(8));
        shadowRadius = slider(page, "Радиус тени", window.shadowRadiusDp,
                0, 96, " dp");
        page.addView(colorButton("Цвет тени", shadowColor, value -> shadowColor = value),
                topMargin(8));
        page.addView(hint("Фон вне скруглённого окна остаётся прозрачным; лаунчер, строка "
                + "состояния и системные панели вокруг карты не закрываются."), topMargin(6));

        page.addView(section("Элементы управления"), topMargin(20));
        modeButtonVisible = toggle("Кнопка окно / полный экран", window.modeButtonVisible);
        dragHandleVisible = toggle("Ручка перемещения, когда окно не зафиксировано",
                window.dragHandleVisible);
        resizeHandleVisible = toggle("Уголок размера, когда окно не зафиксировано",
                window.resizeHandleVisible);
        closeButtonVisible = toggle("Кнопка закрытия", window.closeButtonVisible);
        page.addView(modeButtonVisible, topMargin(5));
        page.addView(dragHandleVisible, topMargin(5));
        page.addView(resizeHandleVisible, topMargin(5));
        page.addView(closeButtonVisible, topMargin(5));
        page.addView(hint("Кнопка режима находится слева под штатными кнопками дорожного "
                + "события и голосового помощника. Она появляется по касанию карты и "
                + "скрывается вместе с ними в обоих режимах. Размер, фон и прозрачность "
                + "берутся у штатного блока Навигатора."), topMargin(6));

        MaterialButton save = new MaterialButton(this);
        save.setAllCaps(false);
        save.setText("Сохранить и применить");
        save.setTextSize(17);
        save.setOnClickListener(view -> save());
        page.addView(save, topMargin(24));
        return scroll;
    }

    @NonNull
    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Оконный режим Навигатора", 23, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, wrap(), 1f);
        row.addView(title, titleParams);
        return row;
    }

    private void save() {
        window.enabled = enabled.isChecked();
        boolean fullyLocked = locked.isChecked();
        window.movementLocked = fullyLocked;
        window.resizeLocked = fullyLocked;
        window.aspectRatioLocked = aspectLocked.isChecked();
        window.rememberGeometry = rememberGeometry.isChecked();
        window.leftPercent = left.value();
        window.topPercent = top.value();
        window.widthPercent = width.value();
        window.heightPercent = height.value();
        window.cornerRadiusDp = cornerRadius.value();
        window.opacityPercent = opacity.value();
        window.borderWidthDp = borderWidth.value();
        window.borderColor = borderColor;
        window.shadowRadiusDp = shadowRadius.value();
        window.shadowColor = shadowColor;
        window.modeButtonVisible = modeButtonVisible.isChecked();
        window.dragHandleVisible = dragHandleVisible.isChecked();
        window.resizeHandleVisible = resizeHandleVisible.isChecked();
        window.closeButtonVisible = closeButtonVisible.isChecked();
        navigation.normalize();
        try {
            String encoded = navigation.toJson().toString();
            if (!preferences.navigationIntegrationConfigJson.commit(encoded)
                    || !encoded.equals(preferences.navigationIntegrationConfigJson.get())) {
                throw new JSONException("контрольное чтение настроек не совпало");
            }
        } catch (JSONException error) {
            Toast.makeText(this, "Не удалось сохранить настройки окна",
                    Toast.LENGTH_LONG).show();
            return;
        }
        NavigationHudEndpointService.requestConfigurationRefresh(this);
        Toast.makeText(this, fullyLocked
                        ? "Окно зафиксировано, ручки скрыты"
                        : "Настройки окна Навигатора применены",
                Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private MaterialButton colorButton(@NonNull String title, @NonNull String initial,
                                       @NonNull ColorSelection selection) {
        MaterialButton button = new MaterialButton(this);
        final String[] current = {initial};
        AppleColorPickerDialog.decorateButton(button, title, current[0]);
        button.setOnClickListener(view -> AppleColorPickerDialog.show(this, title, current[0],
                AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {}

                    @Override public void onSelected(@Nullable String value) {
                        if (value == null) return;
                        current[0] = value;
                        selection.set(value);
                        AppleColorPickerDialog.decorateButton(button, title, value);
                    }
                }));
        return button;
    }

    private interface ColorSelection { void set(@NonNull String value); }

    @NonNull
    private SliderField slider(@NonNull LinearLayout host, @NonNull String title,
                               int value, int minimum, int maximum,
                               @NonNull String suffix) {
        TextView caption = label("");
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, value - minimum)));
        IntValue current = () -> minimum + seek.getProgress();
        Runnable update = () -> caption.setText(title + ": " + current.get() + suffix);
        update.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                update.run();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        host.addView(caption, topMargin(9));
        host.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        return new SliderField(current);
    }

    @NonNull
    private MaterialSwitch toggle(@NonNull String title, boolean checked) {
        MaterialSwitch result = new MaterialSwitch(this);
        result.setText(title);
        result.setTextColor(Color.WHITE);
        result.setTextSize(16);
        result.setChecked(checked);
        result.setPadding(0, dp(3), 0, dp(3));
        return result;
    }

    @NonNull
    private TextView section(@NonNull String value) {
        TextView result = text(value, 20, Color.WHITE);
        result.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return result;
    }

    @NonNull
    private TextView label(@NonNull String value) {
        return text(value, 15, 0xFFD5DCE6);
    }

    @NonNull
    private TextView hint(@NonNull String value) {
        TextView result = text(value, 13, 0xFF95A0AF);
        result.setLineSpacing(0f, 1.12f);
        return result;
    }

    @NonNull
    private TextView text(@NonNull String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        return result;
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

    @NonNull
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    @NonNull
    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams result = matchWrap();
        result.topMargin = dp(value);
        return result;
    }

    private static final class SliderField {
        @NonNull private final IntValue value;

        SliderField(@NonNull IntValue value) {
            this.value = value;
        }

        int value() {
            return value.get();
        }
    }
}
