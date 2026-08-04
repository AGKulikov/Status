/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextUtils;
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
            return new Model("Сообщения и социальные сети",
                    "Алексей — очень длинное имя отправителя",
                    "Это намеренно длинный пример текста уведомления с iPhone: он показывает, "
                            + "как работают ограничение строк, многоточие и автопрокрутка.",
                    null);
        }

        @NonNull private static String clean(@Nullable String value, @NonNull String fallback) {
            String result = value == null ? "" : value.trim();
            return result.isEmpty() ? fallback : result;
        }
    }

    private PhoneNotificationLayoutConfig config;
    private Model model = Model.preview();
    private final TextView avatar;
    private final RoundedIconView badge;
    private final OverflowTextView title;
    private final OverflowTextView time;
    private final OverflowTextView application;
    private final OverflowTextView message;
    private final TextView chevron;
    private final Path surfaceClip = new Path();
    private final RectF surfaceBounds = new RectF();
    private int surfaceCornerRadiusPx;

    public PhoneNotificationCardView(@NonNull Context context) {
        super(context);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setClipChildren(true);
        setClipToPadding(true);

        avatar = text(Gravity.CENTER, 1);
        badge = new RoundedIconView(context);
        title = overflowText(Gravity.START | Gravity.CENTER_VERTICAL);
        time = overflowText(Gravity.START | Gravity.CENTER_VERTICAL);
        application = overflowText(Gravity.START | Gravity.CENTER_VERTICAL);
        message = overflowText(Gravity.START | Gravity.CENTER_VERTICAL);
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
        if (value.borderWidthPx > 0) {
            surface.setStroke(value.borderWidthPx, color(value.borderColor, Color.WHITE));
        }
        setBackground(surface);
        surfaceCornerRadiusPx = value.cornerRadiusPx;
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(value.cornerRadiusPx > 0);

        String initial = model.title.isEmpty() ? "•"
                : model.title.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        avatar.setText(initial);
        avatar.setBackground(round(value.avatarColor, value.avatarCornerRadiusPx));
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        avatar.setClipToOutline(value.avatarCornerRadiusPx > 0);
        // ViewOutlineProvider.BACKGROUND does not reliably clip a fully transparent
        // GradientDrawable on the KX11 Android 9 compositor.  Clip the icon's complete draw pass
        // with the configured path so the slider changes both the editor and the real overlay.
        badge.setBackgroundColor(Color.TRANSPARENT);
        badge.setCornerRadiusPx(value.iconCornerRadiusPx);
        badge.setScaleType(value.iconPreserveAspectRatio
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
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
            if (text instanceof OverflowTextView) {
                ((OverflowTextView) text).configure(element.maxLines, element.overflowMode);
            }
        }
    }

    /**
     * Clips the complete card, including children, to the same path as its drawable. This is an
     * actual translucent-window cut-out; no black parent rectangle is used to fake the corners.
     */
    @Override
    public void draw(@NonNull Canvas canvas) {
        if (surfaceCornerRadiusPx <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            super.draw(canvas);
            return;
        }
        surfaceBounds.set(0f, 0f, getWidth(), getHeight());
        surfaceClip.reset();
        surfaceClip.addRoundRect(surfaceBounds, surfaceCornerRadiusPx, surfaceCornerRadiusPx,
                Path.Direction.CW);
        int checkpoint = canvas.save();
        canvas.clipPath(surfaceClip);
        super.draw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutElements(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // The KX11 Android 9 FrameLayout can keep an ImageView at the position produced by its
        // temporary MATCH_PARENT parameters when this editor is opened again.  The edit overlay
        // already draws from the persisted grid, so that stale child position makes the bitmap
        // appear in the middle while its blue frame stays at the saved cell.  Re-apply the exact
        // grid bounds after every parent layout (not only after a size change) so the rendered
        // child and editor outline always share one coordinate source.
        layoutElementsExactly(right - left, bottom - top);
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

    private void layoutElementsExactly(int width, int height) {
        PhoneNotificationLayoutConfig value = config;
        if (value == null || width <= 0 || height <= 0) return;
        placeExactly(avatar, value.avatar, width, height);
        placeExactly(badge, value.badge, width, height);
        placeExactly(title, value.title, width, height);
        placeExactly(time, value.time, width, height);
        placeExactly(application, value.application, width, height);
        placeExactly(message, value.message, width, height);
        placeExactly(chevron, value.chevron, width, height);
    }

    private static void place(@NonNull View view,
                              @NonNull PhoneNotificationLayoutConfig.Element element,
                              int width, int height) {
        int childLeft = gridCoordinate(element.column, width,
                PhoneNotificationLayoutConfig.GRID_COLUMNS);
        int childRight = gridCoordinate(element.column + element.columnSpan, width,
                PhoneNotificationLayoutConfig.GRID_COLUMNS);
        int childTop = gridCoordinate(element.row, height,
                PhoneNotificationLayoutConfig.GRID_ROWS);
        int childBottom = gridCoordinate(element.row + element.rowSpan, height,
                PhoneNotificationLayoutConfig.GRID_ROWS);
        if (view instanceof OverflowTextView) {
            childBottom = Math.min(childBottom, childTop
                    + ((OverflowTextView) view).preferredVisibleHeight());
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, childRight - childLeft), Math.max(1, childBottom - childTop));
        params.leftMargin = childLeft;
        params.topMargin = childTop;
        view.setLayoutParams(params);
    }

    private static void placeExactly(@NonNull View view,
                                     @NonNull PhoneNotificationLayoutConfig.Element element,
                                     int width, int height) {
        if (view.getVisibility() == GONE) return;
        int childLeft = gridCoordinate(element.column, width,
                PhoneNotificationLayoutConfig.GRID_COLUMNS);
        int childRight = gridCoordinate(element.column + element.columnSpan, width,
                PhoneNotificationLayoutConfig.GRID_COLUMNS);
        int childTop = gridCoordinate(element.row, height,
                PhoneNotificationLayoutConfig.GRID_ROWS);
        int childBottom = gridCoordinate(element.row + element.rowSpan, height,
                PhoneNotificationLayoutConfig.GRID_ROWS);
        if (view instanceof OverflowTextView) {
            childBottom = Math.min(childBottom, childTop
                    + ((OverflowTextView) view).preferredVisibleHeight());
        }
        int childWidth = Math.max(1, childRight - childLeft);
        int childHeight = Math.max(1, childBottom - childTop);
        if (view.getMeasuredWidth() != childWidth || view.getMeasuredHeight() != childHeight) {
            view.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
    }

    private static int gridCoordinate(int index, int extent, int cellCount) {
        return Math.round(index * (Math.max(1, extent) / (float) Math.max(1, cellCount)));
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

    @NonNull
    private OverflowTextView overflowText(int gravity) {
        OverflowTextView value = new OverflowTextView(getContext());
        value.setGravity(gravity);
        value.setIncludeFontPadding(false);
        return value;
    }

    /** Multi-line ellipsis or bounded automatic vertical scrolling on Android 9. */
    private static final class OverflowTextView extends TextView {
        private static final long FRAME_MS = 40L;
        private static final long EDGE_PAUSE_MS = 900L;
        private int visibleLines = 1;
        private boolean autoScroll;
        private int direction = 1;
        private long pausedUntil;
        private final Runnable scrollFrame = new Runnable() {
            @Override public void run() {
                if (!autoScroll || !isAttachedToWindow()) return;
                Layout layout = getLayout();
                int viewport = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
                int range = layout == null ? 0 : Math.max(0, layout.getHeight() - viewport);
                long now = android.os.SystemClock.uptimeMillis();
                if (range <= 0) {
                    scrollTo(0, 0);
                    postDelayed(this, EDGE_PAUSE_MS);
                    return;
                }
                if (now < pausedUntil) {
                    postDelayed(this, Math.min(FRAME_MS, pausedUntil - now));
                    return;
                }
                int step = Math.max(1, Math.round(
                        getResources().getDisplayMetrics().density));
                int next = Math.max(0, Math.min(range, getScrollY() + direction * step));
                scrollTo(0, next);
                if (next == 0 || next == range) {
                    direction = next == range ? -1 : 1;
                    pausedUntil = now + EDGE_PAUSE_MS;
                }
                postDelayed(this, FRAME_MS);
            }
        };

        OverflowTextView(@NonNull Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
        }

        void configure(int maxLines, @NonNull String overflowMode) {
            visibleLines = Math.max(1, Math.min(8, maxLines));
            autoScroll = PhoneNotificationLayoutConfig.OVERFLOW_SCROLL.equals(overflowMode);
            removeCallbacks(scrollFrame);
            direction = 1;
            pausedUntil = android.os.SystemClock.uptimeMillis() + EDGE_PAUSE_MS;
            scrollTo(0, 0);
            setHorizontallyScrolling(false);
            setSingleLine(false);
            if (autoScroll) {
                setMaxLines(Integer.MAX_VALUE);
                setEllipsize(null);
                if (isAttachedToWindow()) post(scrollFrame);
            } else {
                setMaxLines(visibleLines);
                setEllipsize(TextUtils.TruncateAt.END);
            }
        }

        int preferredVisibleHeight() {
            return Math.max(1, getLineHeight() * visibleLines
                    + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (autoScroll) post(scrollFrame);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(scrollFrame);
            super.onDetachedFromWindow();
        }
    }

    /** Deterministic rounded-square clipping for bitmap and vector application icons. */
    private static final class RoundedIconView extends ImageView {
        private final RectF maskBounds = new RectF();
        private final Paint roundedPaint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG);
        @Nullable private Bitmap iconLayer;
        private int cornerRadiusPx;

        RoundedIconView(@NonNull Context context) {
            super(context);
        }

        void setCornerRadiusPx(int radius) {
            int safe = Math.max(0, radius);
            if (cornerRadiusPx == safe) return;
            cornerRadiusPx = safe;
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (getWidth() <= 0 || getHeight() <= 0 || getDrawable() == null) return;
            ensureIconLayer();
            Bitmap layer = iconLayer;
            if (layer == null) return;

            // Draw ImageView's matrix into an ordinary ARGB bitmap first.  The final operation is
            // a rounded primitive filled by a BitmapShader, so neither clipToOutline, clipPath nor
            // an OEM-dependent PorterDuff layer participates in the visible result.
            layer.eraseColor(Color.TRANSPARENT);
            Canvas iconCanvas = new Canvas(layer);
            int iconCheckpoint = iconCanvas.save();
            iconCanvas.translate(getPaddingLeft(), getPaddingTop());
            iconCanvas.concat(getImageMatrix());
            getDrawable().draw(iconCanvas);
            iconCanvas.restoreToCount(iconCheckpoint);

            float radius = Math.min(cornerRadiusPx,
                    Math.min(getWidth(), getHeight()) / 2f);
            maskBounds.set(0f, 0f, getWidth(), getHeight());
            BitmapShader shader = new BitmapShader(layer,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            roundedPaint.setShader(shader);
            canvas.drawRoundRect(maskBounds, radius, radius, roundedPaint);
            roundedPaint.setShader(null);
        }

        private void ensureIconLayer() {
            Bitmap current = iconLayer;
            if (current != null && current.getWidth() == getWidth()
                    && current.getHeight() == getHeight()) return;
            if (current != null) current.recycle();
            iconLayer = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        }

        @Override protected void onDetachedFromWindow() {
            Bitmap current = iconLayer;
            iconLayer = null;
            if (current != null) current.recycle();
            super.onDetachedFromWindow();
        }
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
