/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * One visual, dependency-free color picker for every settings surface.
 *
 * <p>Its interaction follows the familiar Apple color sheet: a large live sample, a compact
 * palette, H/S/B/opacity controls and an exact HEX field. Semantic values remain first-class
 * choices instead of being flattened into an ARGB number.</p>
 */
public final class AppleColorPickerDialog {
    private static final int APPLE_BLUE = 0xFF0A84FF;
    private static final int[] SWATCHES = {
            0xFFFFFFFF, 0xFFC7C7CC, 0xFF8E8E93, 0xFF3A3A3C, 0xFF000000,
            0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF,
            0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFAC8E68,
            0xFF35B7FF, 0xFF00C853, 0xFFFFB300, 0xFF9C6BFF, 0xFF121923
    };

    private AppleColorPickerDialog() {}

    public interface Listener {
        /** Called while the user explores colors, so the owning screen can update its preview. */
        void onPreview(@Nullable String value);

        /** Called after Apply. */
        void onSelected(@Nullable String value);

        /** Called after Cancel with the exact original value. */
        default void onCancelled(@Nullable String originalValue) {
            onPreview(originalValue);
        }
    }

    public static final class Options {
        public final boolean allowTransparent;
        public final boolean allowNone;
        public final boolean allowInherit;
        public final boolean allowAlpha;
        @NonNull public final String inheritLabel;

        private Options(boolean allowTransparent, boolean allowNone, boolean allowInherit,
                        boolean allowAlpha, @NonNull String inheritLabel) {
            this.allowTransparent = allowTransparent;
            this.allowNone = allowNone;
            this.allowInherit = allowInherit;
            this.allowAlpha = allowAlpha;
            this.inheritLabel = inheritLabel;
        }

        @NonNull
        public static Options standard() {
            return new Options(true, false, false, true, "Не менять");
        }

        /** RGB-only picker for surfaces that expose opacity as a separate visual control. */
        @NonNull
        public static Options opaque() {
            return new Options(false, false, false, false, "Не менять");
        }

        @NonNull
        public static Options noTint() {
            return new Options(true, true, false, true, "Не менять");
        }

        @NonNull
        public static Options inheritable() {
            return new Options(true, false, true, true, "Не менять исходный цвет");
        }

        @NonNull
        public Options withAlpha(boolean enabled) {
            return new Options(allowTransparent, allowNone, allowInherit, enabled, inheritLabel);
        }

        @NonNull
        public Options withInheritLabel(@NonNull String label) {
            return new Options(allowTransparent, allowNone, allowInherit, allowAlpha, label);
        }
    }

    public static void show(@NonNull Activity activity, @NonNull String title,
                            @Nullable String current, @NonNull Options options,
                            @NonNull Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new Controller(activity, title, current, options, listener).show();
    }

    /** Applies the same readable value and visual color sample to every settings button. */
    public static void decorateButton(@NonNull MaterialButton button, @NonNull String title,
                                      @Nullable String value) {
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setText(title + "\n" + SettingsColorValue.displayValue(value));
        button.setTextSize(15);
        button.setIcon(new ColorSampleDrawable(value));
        button.setIconTint(null);
        button.setIconSize(dp(button.getContext(), 28));
        button.setIconPadding(dp(button.getContext(), 12));
    }

    private static final class Controller {
        private final Activity activity;
        private final String title;
        @Nullable private final String original;
        private final Options options;
        private final Listener listener;
        private final float[] hsv = new float[3];
        private int alpha;
        private boolean explicitAlpha;
        @NonNull private SettingsColorValue.Kind kind;
        private boolean internalUpdate;

        private PreviewView preview;
        private EditText hex;
        private TextView hueValue;
        private TextView saturationValue;
        private TextView brightnessValue;
        private TextView alphaValue;
        private SeekBar hue;
        private SeekBar saturation;
        private SeekBar brightness;
        private SeekBar opacity;
        private AlertDialog dialog;

        Controller(@NonNull Activity activity, @NonNull String title, @Nullable String current,
                   @NonNull Options options, @NonNull Listener listener) {
            this.activity = activity;
            this.title = title;
            this.original = current;
            this.options = options;
            this.listener = listener;
            SettingsColorValue fallback = SettingsColorValue.color(Color.WHITE, false);
            SettingsColorValue parsed = SettingsColorValue.parseOr(current, fallback);
            kind = validKind(parsed.kind()) ? parsed.kind() : SettingsColorValue.Kind.COLOR;
            int color = parsed.kind() == SettingsColorValue.Kind.COLOR
                    || parsed.kind() == SettingsColorValue.Kind.TRANSPARENT
                    ? parsed.argb() : Color.WHITE;
            alpha = Color.alpha(color);
            explicitAlpha = parsed.hasExplicitAlpha();
            Color.colorToHSV(color | 0xFF000000, hsv);
            if (kind == SettingsColorValue.Kind.TRANSPARENT) alpha = 0;
        }

        private boolean validKind(@NonNull SettingsColorValue.Kind candidate) {
            if (candidate == SettingsColorValue.Kind.TRANSPARENT) return options.allowTransparent;
            if (candidate == SettingsColorValue.Kind.NONE) return options.allowNone;
            if (candidate == SettingsColorValue.Kind.INHERIT) return options.allowInherit;
            return true;
        }

        void show() {
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(buildContent())
                    .setNegativeButton("Отмена", (d, which) -> listener.onCancelled(original))
                    .setPositiveButton("Применить", null)
                    .create();
            dialog.setOnCancelListener(d -> listener.onCancelled(original));
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(APPLE_BLUE);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    SettingsColorValue typed = parseTypedValue();
                    if (typed == null) {
                        hex.setError("Введите #RRGGBB или #AARRGGBB");
                        return;
                    }
                    applyParsed(typed, false);
                    listener.onSelected(serialized());
                    dialog.dismiss();
                });
            });
            dialog.show();
        }

        @NonNull
        private View buildContent() {
            Context context = activity;
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(context, 22), dp(context, 8), dp(context, 22), dp(context, 18));
            scroll.addView(root, new ScrollView.LayoutParams(match(), wrap()));

            preview = new PreviewView(context);
            preview.setColor(currentArgb());
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(match(), dp(context, 108));
            previewLp.bottomMargin = dp(context, 16);
            root.addView(preview, previewLp);

            addSpecialChoices(root);
            TextView paletteTitle = sectionLabel(context, "ЦВЕТА");
            root.addView(paletteTitle, topMargin(context, 8));
            GridLayout palette = new GridLayout(context);
            palette.setColumnCount(10);
            palette.setUseDefaultMargins(false);
            for (int color : SWATCHES) palette.addView(swatch(context, color));
            root.addView(palette, new LinearLayout.LayoutParams(match(), wrap()));

            hue = slider(root, "Оттенок", 360, Math.round(hsv[0]), value -> {
                hsv[0] = value;
                chooseColor();
            });
            hueValue = (TextView) hue.getTag();
            saturation = slider(root, "Насыщенность", 100, Math.round(hsv[1] * 100f), value -> {
                hsv[1] = value / 100f;
                chooseColor();
            });
            saturationValue = (TextView) saturation.getTag();
            brightness = slider(root, "Яркость", 100, Math.round(hsv[2] * 100f), value -> {
                hsv[2] = value / 100f;
                chooseColor();
            });
            brightnessValue = (TextView) brightness.getTag();
            if (options.allowAlpha) {
                opacity = slider(root, "Непрозрачность", 100,
                        Math.round(alpha * 100f / 255f), value -> {
                            alpha = Math.round(value * 255f / 100f);
                            explicitAlpha = true;
                            chooseColor();
                        });
                alphaValue = (TextView) opacity.getTag();
            }

            TextView exact = sectionLabel(context, "ТОЧНОЕ ЗНАЧЕНИЕ");
            root.addView(exact, topMargin(context, 12));
            hex = new EditText(context);
            hex.setSingleLine(true);
            hex.setTextSize(17);
            hex.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            hex.setHint("#RRGGBB или #AARRGGBB");
            hex.setText(serialized());
            hex.setSelection(hex.length());
            hex.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable editable) {
                    if (internalUpdate) return;
                    SettingsColorValue parsed = parseTypedValue();
                    if (parsed != null) {
                        hex.setError(null);
                        applyParsed(parsed, true);
                    }
                }
            });
            root.addView(hex, new LinearLayout.LayoutParams(match(), dp(context, 54)));
            return scroll;
        }

        private void addSpecialChoices(@NonNull LinearLayout root) {
            if (!options.allowTransparent && !options.allowNone && !options.allowInherit) return;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (options.allowInherit) {
                row.addView(specialButton(options.inheritLabel, SettingsColorValue.Kind.INHERIT),
                        weighted());
            }
            if (options.allowNone) {
                row.addView(specialButton("Без окрашивания", SettingsColorValue.Kind.NONE),
                        weighted());
            }
            if (options.allowTransparent) {
                row.addView(specialButton("Прозрачный", SettingsColorValue.Kind.TRANSPARENT),
                        weighted());
            }
            root.addView(row, new LinearLayout.LayoutParams(match(), dp(activity, 46)));
        }

        @NonNull
        private MaterialButton specialButton(@NonNull String label,
                                             @NonNull SettingsColorValue.Kind selectedKind) {
            MaterialButton button = new MaterialButton(activity);
            button.setAllCaps(false);
            button.setText(label);
            button.setTextSize(13);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setOnClickListener(v -> {
                kind = selectedKind;
                if (selectedKind == SettingsColorValue.Kind.TRANSPARENT) alpha = 0;
                refresh(false);
            });
            return button;
        }

        @NonNull
        private View swatch(@NonNull Context context, @ColorInt int color) {
            View swatch = new View(context);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(dp(context, 1), Color.argb(90, 128, 128, 128));
            swatch.setBackground(background);
            swatch.setContentDescription(SettingsColorValue.serializeColor(color, false));
            swatch.setOnClickListener(v -> {
                alpha = Color.alpha(color);
                explicitAlpha = false;
                Color.colorToHSV(color, hsv);
                kind = SettingsColorValue.Kind.COLOR;
                refresh(false);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(context, 42);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
            swatch.setLayoutParams(lp);
            return swatch;
        }

        @NonNull
        private SeekBar slider(@NonNull LinearLayout root, @NonNull String label, int max,
                               int progress, @NonNull IntListener listener) {
            LinearLayout labels = new LinearLayout(activity);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(activity);
            name.setText(label);
            name.setTextSize(15);
            TextView value = new TextView(activity);
            value.setText(sliderValue(label, progress));
            value.setTextSize(14);
            value.setGravity(Gravity.END);
            labels.addView(name, weighted());
            labels.addView(value, new LinearLayout.LayoutParams(dp(activity, 72), wrap()));
            root.addView(labels, topMargin(activity, 9));

            SeekBar bar = new SeekBar(activity);
            bar.setMax(max);
            bar.setProgress(progress);
            bar.setTag(value);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int selected,
                                                        boolean fromUser) {
                    value.setText(sliderValue(label, selected));
                    if (fromUser && !internalUpdate) listener.onValue(selected);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(bar, new LinearLayout.LayoutParams(match(), dp(activity, 38)));
            return bar;
        }

        private void chooseColor() {
            kind = SettingsColorValue.Kind.COLOR;
            refresh(false);
        }

        private void applyParsed(@NonNull SettingsColorValue parsed, boolean fromTyping) {
            if (!validKind(parsed.kind())) return;
            kind = parsed.kind();
            if (kind == SettingsColorValue.Kind.COLOR
                    || kind == SettingsColorValue.Kind.TRANSPARENT) {
                int color = parsed.argb();
                alpha = Color.alpha(color);
                explicitAlpha = parsed.hasExplicitAlpha();
                Color.colorToHSV(color | 0xFF000000, hsv);
            }
            refresh(fromTyping);
        }

        private void refresh(boolean preserveTypedText) {
            internalUpdate = true;
            int color = currentArgb();
            preview.setColor(color);
            if (hue != null) hue.setProgress(Math.round(hsv[0]));
            if (saturation != null) saturation.setProgress(Math.round(hsv[1] * 100f));
            if (brightness != null) brightness.setProgress(Math.round(hsv[2] * 100f));
            if (opacity != null) opacity.setProgress(Math.round(alpha * 100f / 255f));
            if (!preserveTypedText && hex != null) {
                hex.setText(serialized());
                hex.setSelection(hex.length());
            }
            internalUpdate = false;
            listener.onPreview(serialized());
        }

        @Nullable
        private SettingsColorValue parseTypedValue() {
            if (hex == null) return currentValue();
            String value = hex.getText().toString().trim();
            if (value.isEmpty() && options.allowInherit) return SettingsColorValue.inherit();
            SettingsColorValue parsed = SettingsColorValue.tryParse(value);
            return parsed != null && validKind(parsed.kind()) ? parsed : null;
        }

        @NonNull
        private SettingsColorValue currentValue() {
            switch (kind) {
                case INHERIT: return SettingsColorValue.inherit();
                case NONE: return SettingsColorValue.none();
                case TRANSPARENT: return SettingsColorValue.transparent();
                case COLOR:
                default: return SettingsColorValue.color(currentArgb(), explicitAlpha);
            }
        }

        @Nullable
        private String serialized() {
            return currentValue().serialize();
        }

        @ColorInt
        private int currentArgb() {
            if (kind == SettingsColorValue.Kind.TRANSPARENT) return Color.TRANSPARENT;
            return Color.HSVToColor(alpha, hsv);
        }
    }

    private interface IntListener { void onValue(int value); }

    private static final class PreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @ColorInt private int color;

        PreviewView(@NonNull Context context) {
            super(context);
            setContentDescription("Предпросмотр выбранного цвета");
        }

        void setColor(@ColorInt int value) {
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            RectF bounds = new RectF(0, 0, getWidth(), getHeight());
            float radius = dp(getContext(), 18);
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            drawCheckerboard(canvas, new Rect(0, 0, getWidth(), getHeight()), paint,
                    dp(getContext(), 14));
            paint.setColor(color);
            canvas.drawRect(bounds, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 1));
            paint.setColor(Color.argb(100, 128, 128, 128));
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private static final class ColorSampleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @ColorInt private final int color;

        ColorSampleDrawable(@Nullable String raw) {
            SettingsColorValue parsed = SettingsColorValue.tryParse(raw);
            color = parsed != null && parsed.kind() == SettingsColorValue.Kind.COLOR
                    ? parsed.argb() : Color.TRANSPARENT;
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float radius = Math.min(bounds.width(), bounds.height()) * .28f;
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            drawCheckerboard(canvas, bounds, paint, Math.max(3, bounds.width() / 5));
            paint.setColor(color);
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, bounds.width() / 18f));
            paint.setColor(Color.argb(120, 128, 128, 128));
            canvas.drawRoundRect(new RectF(bounds), radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 32; }
        @Override public int getIntrinsicHeight() { return 32; }
    }

    private static void drawCheckerboard(@NonNull Canvas canvas, @NonNull Rect bounds,
                                         @NonNull Paint paint, int cell) {
        for (int y = bounds.top; y < bounds.bottom; y += cell) {
            for (int x = bounds.left; x < bounds.right; x += cell) {
                boolean dark = (((x - bounds.left) / cell) + ((y - bounds.top) / cell)) % 2 == 0;
                paint.setColor(dark ? 0xFFE5E5EA : 0xFFF7F7FA);
                canvas.drawRect(x, y, Math.min(x + cell, bounds.right),
                        Math.min(y + cell, bounds.bottom), paint);
            }
        }
    }

    @NonNull
    private static TextView sectionLabel(@NonNull Context context, @NonNull String value) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(12);
        label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        label.setTextColor(APPLE_BLUE);
        label.setLetterSpacing(.06f);
        return label;
    }

    @NonNull
    private static LinearLayout.LayoutParams topMargin(@NonNull Context context, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(context, marginDp);
        return lp;
    }

    @NonNull
    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, match(), 1f);
    }

    @NonNull
    private static String sliderValue(@NonNull String label, int value) {
        return "Оттенок".equals(label) ? value + "°" : value + "%";
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
