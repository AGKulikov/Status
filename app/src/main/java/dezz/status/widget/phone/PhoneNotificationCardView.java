/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
    private final AppleContinuousSurfaceDrawable surface =
            new AppleContinuousSurfaceDrawable();

    public PhoneNotificationCardView(@NonNull Context context) {
        super(context);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setClipChildren(true);
        setClipToPadding(true);
        // Keep the card background as a real Drawable. The previous mutable bitmap shader was
        // dropped by the KX11 Android 9 overlay compositor, which made both the fill and stroke
        // disappear even though the settings still contained valid colours and alpha.
        setBackground(surface);

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
        int surfaceColor = (base & 0x00FFFFFF) | (value.backgroundAlpha << 24);
        surface.configure(surfaceColor, value.cornerRadiusPx, value.borderWidthPx,
                color(value.borderColor, Color.WHITE));
        if (getBackground() != surface) setBackground(surface);

        String initial = model.title.isEmpty() ? "•"
                : model.title.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        avatar.setText(initial);
        avatar.setBackground(round(value.avatarColor, value.avatarCornerRadiusPx));
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        avatar.setClipToOutline(value.avatarCornerRadiusPx > 0);
        // The card and icon share the same normalized Apple path. The card draws that path
        // directly; the icon publishes a different exact-size Bitmap whose corner pixels already
        // have zero alpha. KX11 therefore never receives the original square drawable.
        badge.setBackgroundColor(Color.TRANSPARENT);
        badge.setContinuousCornerRadiusPx(value.iconCornerRadiusPx);
        badge.setPreserveAspectRatio(value.iconPreserveAspectRatio);
        Drawable icon = phoneAppIcon(model.appIconIdentifier);
        if (icon == null) icon = PhoneNotificationPreviewIconFactory.create(
                getContext(), Math.max(24, value.badge.columnSpan * 24));
        badge.setSourceDrawable(icon);
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

    /**
     * Real translucent card surface drawn directly into the WindowManager canvas.
     *
     * <p>The continuous contour is the fill itself, not a black corner cover or a shader that
     * can be discarded by the Android 9 overlay compositor. The stroke follows an inset copy of
     * the same contour, so changing background alpha never makes the border or fill disappear.</p>
     */
    private static final class AppleContinuousSurfaceDrawable extends Drawable {
        private final RectF fillBounds = new RectF();
        private final RectF borderBounds = new RectF();
        private final Path fillPath = new Path();
        private final Path borderPath = new Path();
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int fillColor = Color.TRANSPARENT;
        private int cornerRadiusPx;
        private int borderWidthPx;
        private int borderColor = Color.TRANSPARENT;
        private int drawableAlpha = 255;
        private int cachedLeft = Integer.MIN_VALUE;
        private int cachedTop = Integer.MIN_VALUE;
        private int cachedRight = Integer.MIN_VALUE;
        private int cachedBottom = Integer.MIN_VALUE;
        private int cachedRadius = Integer.MIN_VALUE;
        private int cachedBorderWidth = Integer.MIN_VALUE;

        AppleContinuousSurfaceDrawable() {
            fillPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeJoin(Paint.Join.ROUND);
            borderPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void configure(int color, int radius, int borderWidth, int strokeColor) {
            int safeRadius = Math.max(0, radius);
            int safeBorder = Math.max(0, borderWidth);
            boolean geometryChanged = cornerRadiusPx != safeRadius
                    || borderWidthPx != safeBorder;
            fillColor = color;
            cornerRadiusPx = safeRadius;
            borderWidthPx = safeBorder;
            borderColor = strokeColor;
            if (geometryChanged) invalidateGeometry();
            invalidateSelf();
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            updateGeometry(bounds);

            fillPaint.setColor(fillColor);
            fillPaint.setAlpha(Math.round(Color.alpha(fillColor) * (drawableAlpha / 255f)));
            canvas.drawPath(fillPath, fillPaint);

            if (borderWidthPx > 0 && !borderPath.isEmpty()) {
                borderPaint.setStrokeWidth(borderWidthPx);
                borderPaint.setColor(borderColor);
                borderPaint.setAlpha(Math.round(
                        Color.alpha(borderColor) * (drawableAlpha / 255f)));
                canvas.drawPath(borderPath, borderPaint);
            }
        }

        private void updateGeometry(@NonNull Rect bounds) {
            if (cachedLeft == bounds.left && cachedTop == bounds.top
                    && cachedRight == bounds.right && cachedBottom == bounds.bottom
                    && cachedRadius == cornerRadiusPx
                    && cachedBorderWidth == borderWidthPx) return;
            cachedLeft = bounds.left;
            cachedTop = bounds.top;
            cachedRight = bounds.right;
            cachedBottom = bounds.bottom;
            cachedRadius = cornerRadiusPx;
            cachedBorderWidth = borderWidthPx;

            fillBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
            AppleContinuousCornerPath.set(fillPath, fillBounds, cornerRadiusPx);
            borderPath.reset();
            if (borderWidthPx <= 0) return;

            float halfStroke = Math.min(borderWidthPx,
                    Math.min(bounds.width(), bounds.height())) / 2f;
            borderBounds.set(bounds.left + halfStroke, bounds.top + halfStroke,
                    bounds.right - halfStroke, bounds.bottom - halfStroke);
            AppleContinuousCornerPath.set(borderPath, borderBounds,
                    Math.max(0f, cornerRadiusPx
                            - halfStroke
                            / AppleContinuousCornerPath.CONTINUOUS_EXTENT_MULTIPLIER));
        }

        private void invalidateGeometry() {
            cachedLeft = Integer.MIN_VALUE;
            cachedTop = Integer.MIN_VALUE;
            cachedRight = Integer.MIN_VALUE;
            cachedBottom = Integer.MIN_VALUE;
            cachedRadius = Integer.MIN_VALUE;
            cachedBorderWidth = Integer.MIN_VALUE;
        }

        @Override protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            invalidateGeometry();
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            borderPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Publishes an exact-size bitmap whose corner pixels are physically transparent.
     *
     * <p>Do not apply the mask from an {@code onDraw()} override. The Android 9 KX11 compositor can
     * keep the ImageView display list for the original square drawable even when a mutable bitmap
     * is painted over it during the same draw. Instead this view rasterises the source only when
     * its source, size or style changes, multiplies every source alpha byte by a software mask,
     * and gives that finished bitmap to the ordinary ImageView renderer.</p>
     */
    private static final class AppleContinuousIconView extends ImageView {
        private final RectF outputBounds = new RectF();
        private final Path outputPath = new Path();
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int continuousCornerRadiusPx;
        private boolean preserveAspectRatio = true;
        @Nullable private Drawable sourceDrawable;
        @Nullable private Bitmap publishedBitmap;
        private int publishedWidth = -1;
        private int publishedHeight = -1;
        private int publishedRadius = -1;
        private boolean publishedPreserveAspectRatio;
        @Nullable private Drawable publishedSource;

        AppleContinuousIconView(@NonNull Context context) {
            super(context);
            maskPaint.setColor(Color.WHITE);
            maskPaint.setStyle(Paint.Style.FILL);
            // Scaling is performed while rasterising the source. The published bitmap already has
            // the view's exact physical dimensions, so ImageView must not reinterpret its bounds.
            super.setScaleType(ScaleType.FIT_XY);
        }

        void setContinuousCornerRadiusPx(int radius) {
            int safe = Math.max(0, radius);
            if (continuousCornerRadiusPx == safe) return;
            continuousCornerRadiusPx = safe;
            invalidatePublishedBitmap();
            publishIfReady();
        }

        void setPreserveAspectRatio(boolean preserve) {
            if (preserveAspectRatio == preserve) return;
            preserveAspectRatio = preserve;
            invalidatePublishedBitmap();
            publishIfReady();
        }

        void setSourceDrawable(@Nullable Drawable source) {
            sourceDrawable = source;
            invalidatePublishedBitmap();
            if (source == null) super.setImageDrawable(null);
            publishIfReady();
        }

        private void publishIfReady() {
            Drawable source = sourceDrawable;
            int width = getWidth();
            int height = getHeight();
            if (source == null || width <= 0 || height <= 0) return;
            if (publishedBitmap != null && publishedWidth == width
                    && publishedHeight == height && publishedRadius == continuousCornerRadiusPx
                    && publishedPreserveAspectRatio == preserveAspectRatio
                    && publishedSource == source) return;

            Bitmap sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            sourceBitmap.setDensity(getResources().getDisplayMetrics().densityDpi);
            float iconSide = Math.min(width, height);
            float iconLeft = (width - iconSide) / 2f;
            float iconTop = (height - iconSide) / 2f;
            outputBounds.set(iconLeft, iconTop, iconLeft + iconSide, iconTop + iconSide);
            drawSource(source, new Canvas(sourceBitmap), outputBounds);

            Bitmap output = sourceBitmap;
            if (continuousCornerRadiusPx > 0) {
                Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                mask.setDensity(getResources().getDisplayMetrics().densityDpi);
                AppleContinuousCornerPath.setIconMask(
                        outputPath, outputBounds, continuousCornerRadiusPx,
                        PhoneNotificationLayoutConfig.ICON_CORNER_RADIUS_MAX_PX);
                new Canvas(mask).drawPath(outputPath, maskPaint);

                int pixelCount = width * height;
                int[] pixels = new int[pixelCount];
                int[] alphaMask = new int[pixelCount];
                sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                mask.getPixels(alphaMask, 0, width, 0, 0, width, height);
                IconAlphaMask.apply(pixels, alphaMask);

                output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                output.setDensity(getResources().getDisplayMetrics().densityDpi);
                output.setPixels(pixels, 0, width, 0, 0, width, height);
                output.setHasAlpha(true);
            }
            output.prepareToDraw();
            publishedBitmap = output;
            publishedWidth = width;
            publishedHeight = height;
            publishedRadius = continuousCornerRadiusPx;
            publishedPreserveAspectRatio = preserveAspectRatio;
            publishedSource = source;
            // This is the decisive difference from HA1173: the ImageView itself now owns only
            // the completed transparent-corner bitmap, never the original square drawable.
            super.setImageBitmap(output);
        }

        private void drawSource(@NonNull Drawable source, @NonNull Canvas canvas,
                                @NonNull RectF iconBounds) {
            int sourceWidth = source.getIntrinsicWidth();
            int sourceHeight = source.getIntrinsicHeight();
            int boxWidth = Math.max(1, Math.round(iconBounds.width()));
            int boxHeight = Math.max(1, Math.round(iconBounds.height()));
            if (sourceWidth <= 0) sourceWidth = boxWidth;
            if (sourceHeight <= 0) sourceHeight = boxHeight;
            float widthScale = boxWidth / (float) Math.max(1, sourceWidth);
            float heightScale = boxHeight / (float) Math.max(1, sourceHeight);
            float scale = preserveAspectRatio
                    ? Math.min(widthScale, heightScale) : Math.max(widthScale, heightScale);
            int drawWidth = Math.max(1, Math.round(sourceWidth * scale));
            int drawHeight = Math.max(1, Math.round(sourceHeight * scale));
            int left = Math.round(iconBounds.centerX() - drawWidth / 2f);
            int top = Math.round(iconBounds.centerY() - drawHeight / 2f);
            Rect previousBounds = source.copyBounds();
            int checkpoint = canvas.save();
            canvas.clipRect(iconBounds);
            source.setBounds(left, top, left + drawWidth, top + drawHeight);
            source.draw(canvas);
            source.setBounds(previousBounds);
            canvas.restoreToCount(checkpoint);
        }

        private void invalidatePublishedBitmap() {
            publishedBitmap = null;
            publishedWidth = -1;
            publishedHeight = -1;
            publishedRadius = -1;
            publishedSource = null;
        }

        @Override protected void onSizeChanged(int width, int height,
                                               int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            invalidatePublishedBitmap();
            publishIfReady();
        }

        @Override protected void onLayout(boolean changed, int left, int top,
                                          int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            publishIfReady();
        }
    }

    @Nullable
    private Drawable phoneAppIcon(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (PhoneNotificationAutomation.LOW_BATTERY_ICON_ID.equals(raw.trim())) {
            int iconSize = config == null ? 72
                    : Math.max(24, config.badge.columnSpan * 24);
            return PhoneNotificationLowBatteryIconFactory.create(getContext(), iconSize);
        }
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
