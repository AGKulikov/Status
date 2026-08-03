/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Runtime and editor rendering of the same single-piece CarPlay notification card. */
public final class PhoneNotificationCardView extends FrameLayout {
    public static final class Model {
        @NonNull public final String application;
        @NonNull public final String title;
        @NonNull public final String message;
        @Nullable public final String appIconIdentifier;

        public Model(@Nullable String application, @Nullable String title,
                     @Nullable String message, @Nullable String appIconIdentifier) {
            this.application = clean(application, "Telegram");
            this.title = clean(title, "Aleksey");
            this.message = clean(message, "Новое сообщение");
            this.appIconIdentifier = appIconIdentifier == null
                    ? null : appIconIdentifier.trim();
        }

        @NonNull public static Model preview() {
            return new Model("Telegram", "Aleksey", "Тестовое уведомление", null);
        }

        @NonNull private static String clean(@Nullable String value, @NonNull String fallback) {
            String result = value == null ? "" : value.trim();
            return result.isEmpty() ? fallback : result;
        }
    }

    private PhoneNotificationLayoutConfig config;
    private Model model = Model.preview();
    private final TextView avatar;
    private final ImageView badge;
    private final TextView title;
    private final TextView time;
    private final TextView application;
    private final TextView message;
    private final TextView chevron;

    public PhoneNotificationCardView(@NonNull Context context) {
        super(context);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setClipChildren(false);
        setClipToPadding(false);

        avatar = text(Gravity.CENTER, 1);
        badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        title = text(Gravity.START | Gravity.CENTER_VERTICAL, 1);
        time = text(Gravity.START | Gravity.CENTER_VERTICAL, 1);
        application = text(Gravity.START | Gravity.CENTER_VERTICAL, 1);
        message = text(Gravity.START | Gravity.CENTER_VERTICAL, 2);
        chevron = text(Gravity.CENTER, 1);
        chevron.setText("›");
        addView(avatar);
        addView(badge);
        addView(title);
        addView(time);
        addView(application);
        addView(message);
        addView(chevron);
    }

    public void setPresentation(@NonNull PhoneNotificationLayoutConfig config,
                                @NonNull Model model) {
        this.config = config;
        this.model = model;
        applyPresentation();
        if (getWidth() > 0 && getHeight() > 0) layoutElements(getWidth(), getHeight());
        requestLayout();
        invalidate();
    }

    private void applyPresentation() {
        PhoneNotificationLayoutConfig value = config;
        if (value == null) return;
        value.normalize();
        GradientDrawable surface = new GradientDrawable();
        int base = color(value.backgroundColor, 0xFF29292D);
        surface.setColor((base & 0x00FFFFFF) | (value.backgroundAlpha << 24));
        surface.setCornerRadius(value.cornerRadiusPx);
        setBackground(surface);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(value.cornerRadiusPx > 0);

        String initial = model.title.isEmpty() ? "•"
                : model.title.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        avatar.setText(initial);
        avatar.setBackground(round(value.avatarColor, value.avatarCornerRadiusPx));
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        avatar.setClipToOutline(value.avatarCornerRadiusPx > 0);
        badge.setBackground(round("#00000000", value.iconCornerRadiusPx));
        badge.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        badge.setClipToOutline(value.iconCornerRadiusPx > 0);
        Drawable icon = phoneAppIcon(model.appIconIdentifier);
        if (icon == null) icon = PhoneNotificationPreviewIconFactory.create(
                getContext(), Math.max(24, value.badge.columnSpan * 24));
        badge.setImageDrawable(icon);
        title.setText(model.title);
        time.setText("сейчас");
        application.setText(model.application);
        message.setText(model.message);

        apply(value.avatar, avatar);
        apply(value.badge, badge);
        apply(value.title, title);
        apply(value.time, time);
        apply(value.application, application);
        apply(value.message, message);
        apply(value.chevron, chevron);
    }

    private void apply(@NonNull PhoneNotificationLayoutConfig.Element element,
                       @NonNull View view) {
        view.setVisibility(element.visible ? VISIBLE : GONE);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, element.textSizePx);
            text.setTextColor(color(element.color, Color.WHITE));
            text.setTypeface(Typeface.create("sans-serif",
                    element.bold ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutElements(width, height);
    }

    private void layoutElements(int width, int height) {
        PhoneNotificationLayoutConfig value = config;
        if (value == null || width <= 0 || height <= 0) return;
        place(avatar, value.avatar, width, height);
        place(badge, value.badge, width, height);
        place(title, value.title, width, height);
        place(time, value.time, width, height);
        place(application, value.application, width, height);
        place(message, value.message, width, height);
        place(chevron, value.chevron, width, height);
    }

    private static void place(@NonNull View view,
                              @NonNull PhoneNotificationLayoutConfig.Element element,
                              int width, int height) {
        int cellWidth = Math.max(1, width / PhoneNotificationLayoutConfig.GRID_COLUMNS);
        int cellHeight = Math.max(1, height / PhoneNotificationLayoutConfig.GRID_ROWS);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, element.columnSpan * cellWidth),
                Math.max(1, element.rowSpan * cellHeight));
        params.leftMargin = element.column * cellWidth;
        params.topMargin = element.row * cellHeight;
        view.setLayoutParams(params);
    }

    @NonNull
    private TextView text(int gravity, int maxLines) {
        TextView value = new TextView(getContext());
        value.setGravity(gravity);
        value.setIncludeFontPadding(false);
        value.setMaxLines(maxLines);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return value;
    }

    @Nullable
    private Drawable phoneAppIcon(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String identifier = raw.startsWith("phone-app:")
                ? raw.substring("phone-app:".length()).trim() : raw.trim();
        return identifier.isEmpty() ? null
                : PhoneAppIconStore.get(getContext()).drawable(identifier);
    }

    @NonNull
    private static GradientDrawable round(@NonNull String raw, int radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color(raw, Color.TRANSPARENT));
        value.setCornerRadius(Math.max(0, radius));
        return value;
    }

    private static int color(@Nullable String raw, int fallback) {
        try {
            return Color.parseColor(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
