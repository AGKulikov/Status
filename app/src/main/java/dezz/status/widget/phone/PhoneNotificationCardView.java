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
    private final AppleContinuousIconView badge;
    private final OverflowTextView title;
    private final OverflowTextView time;
    private final OverflowTextView application;
    private final OverflowTextView message;
    private final TextView chevron;
    private final Path surfacePath = new Path();
    private final Path surfaceBorderPath = new Path();
    private final RectF surfaceBounds = new RectF();
    private final RectF surfaceBorderBounds = new RectF();
    private final Paint surfaceOutputPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint surfaceBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int surfaceColor = Color.TRANSPARENT;
    private int surfaceCornerRadiusPx;
    private int surfaceBorderWidthPx;
    private int surfaceBorderColor = Color.TRANSPARENT;
    private int surfacePathWidth = -1;
    private int surfacePathHeight = -1;
    private int surfacePathRadius = -1;
    private int surfacePathBorderWidth = -1;
    @Nullable private Bitmap surfaceBuffer;
    @Nullable private Canvas surfaceBufferCanvas;
    @Nullable private BitmapShader surfaceBufferShader;

    public PhoneNotificationCardView(@NonNull Context context) {
        super(context);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setClipChildren(true);
        setClipToPadding(true);
        surfaceBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        surfaceBorderPaint.setStrokeCap(Paint.Cap.ROUND);

        avatar = text(Gravity.CENTER, 1);
        badge = new AppleContinuousIconView(context);
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
        int base = color(value.backgroundColor, 0xFF29292D);
        surfaceColor = (base & 0x00FFFFFF) | (value.backgroundAlpha << 24);
        surfaceCornerRadiusPx = value.cornerRadiusPx;
        surfaceBorderWidthPx = value.borderWidthPx;
        surfaceBorderColor = color(value.borderColor, Color.WHITE);
        // The final card is composited through the same Apple continuous path as its icon. An
        // Android GradientDrawable would reintroduce circular quarter-arcs underneath that mask.
        setBackground(null);
        setOutlineProvider(ViewOutlineProvider.BOUNDS);
        setClipToOutline(false);
        invalidateSurfacePaths();

        String initial = model.title.isEmpty() ? "•"
                : model.title.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        avatar.setText(initial);
        avatar.setBackground(round(value.avatarColor, value.avatarCornerRadiusPx));
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        avatar.setClipToOutline(value.avatarCornerRadiusPx > 0);
        // The card and icon share the same normalized Apple path, but each owns an exact-size
        // render buffer. KX11's Android 9 compositor therefore never has to preserve nested
        // clipPath/saveLayer state across the translucent WindowManager overlay.
        badge.setBackgroundColor(Color.TRANSPARENT);
        badge.setContinuousCornerRadiusPx(value.iconCornerRadiusPx);
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
                // Alignment belongs to the actual one/two-line text inside the configured field,
                // not merely to the maximum-lines viewport.  A one-line value in a three-line
                // element therefore really touches the top when TOP is selected and remains
                // vertically centred when CENTER is selected.
                text.setGravity(Gravity.START
                        | (PhoneNotificationLayoutConfig.TEXT_VERTICAL_TOP.equals(
                        element.verticalAlignment) ? Gravity.TOP : Gravity.CENTER_VERTICAL));
                ((OverflowTextView) text).configure(element.maxLines, element.overflowMode);
            }
        }
    }

    /** Composites the complete card through one real Apple-continuous alpha silhouette. */
    @Override
    public void draw(@NonNull Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            super.draw(canvas);
            return;
        }
        ensureSurfaceBuffer(width, height);
        Bitmap buffer = surfaceBuffer;
        Canvas bufferCanvas = surfaceBufferCanvas;
        BitmapShader shader = surfaceBufferShader;
        if (buffer == null || bufferCanvas == null || shader == null) return;

        // Render the opaque/translucent background and every child first, then cut final pixels
        // by drawing only inside the continuous path. This is not a black corner cover and does
        // not depend on Android 9 outline or hardware clip support.
        buffer.eraseColor(surfaceColor);
        super.draw(bufferCanvas);
        updateSurfacePaths(width, height);
        surfaceOutputPaint.setShader(shader);
        canvas.drawPath(surfacePath, surfaceOutputPaint);
        surfaceOutputPaint.setShader(null);

        if (surfaceBorderWidthPx > 0 && !surfaceBorderPath.isEmpty()) {
            surfaceBorderPaint.setStyle(Paint.Style.STROKE);
            surfaceBorderPaint.setStrokeWidth(surfaceBorderWidthPx);
            surfaceBorderPaint.setColor(surfaceBorderColor);
            canvas.drawPath(surfaceBorderPath, surfaceBorderPaint);
        }
    }

    private void ensureSurfaceBuffer(int width, int height) {
        if (surfaceBuffer != null && surfaceBuffer.getWidth() == width
                && surfaceBuffer.getHeight() == height) return;
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        surfaceBuffer = source;
        surfaceBufferCanvas = new Canvas(source);
        surfaceBufferShader = new BitmapShader(source,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    private void updateSurfacePaths(int width, int height) {
        if (surfacePathWidth == width && surfacePathHeight == height
                && surfacePathRadius == surfaceCornerRadiusPx
                && surfacePathBorderWidth == surfaceBorderWidthPx) return;
        surfacePathWidth = width;
        surfacePathHeight = height;
        surfacePathRadius = surfaceCornerRadiusPx;
        surfacePathBorderWidth = surfaceBorderWidthPx;
        surfaceBounds.set(0f, 0f, width, height);
        AppleContinuousCornerPath.set(surfacePath, surfaceBounds, surfaceCornerRadiusPx);

        surfaceBorderPath.reset();
        if (surfaceBorderWidthPx <= 0) return;
        float halfStroke = Math.min(surfaceBorderWidthPx,
                Math.min(width, height)) / 2f;
        surfaceBorderBounds.set(halfStroke, halfStroke,
                width - halfStroke, height - halfStroke);
        AppleContinuousCornerPath.set(surfaceBorderPath, surfaceBorderBounds,
                Math.max(0f, surfaceCornerRadiusPx
                        - halfStroke / AppleContinuousCornerPath.CONTINUOUS_EXTENT_MULTIPLIER));
    }

    private void invalidateSurfacePaths() {
        surfacePathWidth = -1;
        surfacePathHeight = -1;
        surfacePathRadius = -1;
        surfacePathBorderWidth = -1;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        surfaceBuffer = null;
        surfaceBufferCanvas = null;
        surfaceBufferShader = null;
        invalidateSurfacePaths();
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
            int slotBottom = childBottom;
            int visibleHeight = Math.min(Math.max(1, slotBottom - childTop),
                    ((OverflowTextView) view).preferredVisibleHeight());
            if (PhoneNotificationLayoutConfig.TEXT_VERTICAL_CENTER.equals(
                    element.verticalAlignment)) {
                childTop += Math.max(0, slotBottom - childTop - visibleHeight) / 2;
            }
            childBottom = childTop + visibleHeight;
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
            int slotBottom = childBottom;
            int visibleHeight = Math.min(Math.max(1, slotBottom - childTop),
                    ((OverflowTextView) view).preferredVisibleHeight());
            if (PhoneNotificationLayoutConfig.TEXT_VERTICAL_CENTER.equals(
                    element.verticalAlignment)) {
                childTop += Math.max(0, slotBottom - childTop - visibleHeight) / 2;
            }
            childBottom = childTop + visibleHeight;
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

    /** Exact-size Apple continuous mask for bitmap and vector application icons. */
    private static final class AppleContinuousIconView extends ImageView {
        private final RectF outputBounds = new RectF();
        private final Path outputPath = new Path();
        private final Paint outputPaint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG);
        private int continuousCornerRadiusPx;
        private int pathWidth = -1;
        private int pathHeight = -1;
        private int pathRadius = -1;
        @Nullable private Bitmap renderBuffer;
        @Nullable private Canvas renderCanvas;
        @Nullable private BitmapShader renderShader;

        AppleContinuousIconView(@NonNull Context context) {
            super(context);
        }

        void setContinuousCornerRadiusPx(int radius) {
            int safe = Math.max(0, radius);
            if (continuousCornerRadiusPx == safe) return;
            continuousCornerRadiusPx = safe;
            pathRadius = -1;
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (continuousCornerRadiusPx <= 0 || getWidth() <= 0 || getHeight() <= 0) {
                super.onDraw(canvas);
                return;
            }
            ensureRenderBuffer(getWidth(), getHeight());
            Bitmap buffer = renderBuffer;
            Canvas bufferCanvas = renderCanvas;
            BitmapShader shader = renderShader;
            if (buffer == null || bufferCanvas == null || shader == null) return;

            // Draw ImageView's already-scaled result into exact child coordinates. Drawing that
            // bitmap shader only through the Apple path bakes transparent continuous corners into
            // the final icon pixels without clipPath, a pseudo black cover or a circular arc.
            buffer.eraseColor(Color.TRANSPARENT);
            super.onDraw(bufferCanvas);
            updateOutputPath(getWidth(), getHeight());
            outputPaint.setShader(shader);
            canvas.drawPath(outputPath, outputPaint);
            outputPaint.setShader(null);
        }

        private void updateOutputPath(int width, int height) {
            if (pathWidth == width && pathHeight == height
                    && pathRadius == continuousCornerRadiusPx) return;
            pathWidth = width;
            pathHeight = height;
            pathRadius = continuousCornerRadiusPx;
            outputBounds.set(0f, 0f, width, height);
            AppleContinuousCornerPath.set(outputPath, outputBounds, continuousCornerRadiusPx);
        }

        private void ensureRenderBuffer(int width, int height) {
            if (renderBuffer != null && renderBuffer.getWidth() == width
                    && renderBuffer.getHeight() == height) return;
            // Do not explicitly recycle the previous bitmap. A hardware canvas may still have its
            // texture queued on Android 9; recycling it here caused partially updated masks after
            // reopening or resizing the editor.
            Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            renderBuffer = source;
            renderCanvas = new Canvas(source);
            renderShader = new BitmapShader(source,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        @Override protected void onSizeChanged(int width, int height,
                                               int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            renderBuffer = null;
            renderCanvas = null;
            renderShader = null;
            pathWidth = -1;
            pathHeight = -1;
            pathRadius = -1;
        }

        @Override protected void onDetachedFromWindow() {
            renderBuffer = null;
            renderCanvas = null;
            renderShader = null;
            super.onDetachedFromWindow();
        }
    }

    @Override protected void onDetachedFromWindow() {
        surfaceBuffer = null;
        surfaceBufferCanvas = null;
        surfaceBufferShader = null;
        super.onDetachedFromWindow();
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
