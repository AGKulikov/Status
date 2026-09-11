/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.List;

/** Content measurement shared by the standalone blue maneuver cards on both displays. */
public final class ManeuverCardAutoSizer {
    private static final int MAX_WRAPPED_LINES = 2;

    public static final class Content {
        @NonNull public final String distance;
        @NonNull public final String direction;
        @NonNull public final List<List<String>> badgeRows;
        @NonNull public final String auxiliary;
        public final boolean hasGraphic;
        public final boolean horizontalPrimary;

        public Content(@NonNull String distance, @NonNull String direction,
                       @NonNull List<List<String>> badgeRows,
                       @NonNull String auxiliary, boolean hasGraphic,
                       boolean horizontalPrimary) {
            this.distance = distance;
            this.direction = direction;
            this.badgeRows = badgeRows;
            this.auxiliary = auxiliary;
            this.hasGraphic = hasGraphic;
            this.horizontalPrimary = horizontalPrimary;
        }
    }

    /**
     * Resolves the visible rectangle without changing the configured geometry. The configured
     * width is always the hard maximum; automatic height may grow down to the display boundary.
     */
    public static void resolve(@NonNull RectF maximum, float bottomLimit,
                               @NonNull JSONObject options, float pixelScale,
                               float textScale, @NonNull TextPaint measure,
                               @NonNull Typeface typeface, int fallbackFontSizeSp,
                               @NonNull Content content, @NonNull RectF out) {
        boolean autoWidth = options.optBoolean("autoWidth", false);
        boolean autoHeight = options.optBoolean("autoHeight", false);
        out.set(maximum);
        if ((!autoWidth && !autoHeight) || maximum.isEmpty() || !hasContent(content)) return;

        float px = Math.max(.01f, pixelScale);
        float textPx = Math.max(.01f, textScale);
        float distanceSize = Math.max(1f,
                options.optInt("distanceFontSizeSp", fallbackFontSizeSp) * textPx);
        float directionSize = Math.max(1f,
                options.optInt("directionFontSizeSp", Math.max(8, fallbackFontSizeSp / 2))
                        * textPx);
        float badgeSize = Math.max(1f,
                options.optInt("roadBadgeFontSizeSp", Math.max(8, fallbackFontSizeSp / 2))
                        * textPx);
        float auxiliarySize = Math.max(1f,
                options.optInt("auxiliaryFontSizeSp", Math.max(8, fallbackFontSizeSp / 2))
                        * textPx);
        measure.setTypeface(typeface);

        float outerLeft = Math.max(0, options.optInt("paddingLeftPx", 0)) * px;
        float outerTop = Math.max(0, options.optInt("paddingTopPx", 0)) * px;
        float outerRight = Math.max(0, options.optInt("paddingRightPx", 0)) * px;
        float outerBottom = Math.max(0, options.optInt("paddingBottomPx", 0)) * px;
        float textLeft = Math.max(0, options.optInt("textPaddingLeftPx", 0)) * px;
        float textTop = Math.max(0, options.optInt("textPaddingTopPx", 0)) * px;
        float textRight = Math.max(0, options.optInt("textPaddingRightPx", 0)) * px;
        float textBottom = Math.max(0, options.optInt("textPaddingBottomPx", 0)) * px;
        float textHorizontal = textLeft + textRight;
        float textVertical = textTop + textBottom;
        float rowGap = Math.max(0f, options.optInt("textRowGapPx", 2) * px);
        float componentGap = Math.max(2f * px, rowGap);
        float badgePaddingHorizontal = Math.max(0,
                options.optInt("roadBadgePaddingHorizontalPx", 5)) * px;
        float badgePaddingVertical = Math.max(0,
                options.optInt("roadBadgePaddingVerticalPx", 2)) * px;

        boolean distanceSingleLine = options.optBoolean("distanceSingleLine", true);
        boolean directionSingleLine = options.optBoolean("directionSingleLine", true);
        boolean roadBadgeSingleLine = options.optBoolean("roadBadgeSingleLine", true);
        boolean auxiliarySingleLine = options.optBoolean("auxiliarySingleLine", true);
        float distanceWidth = textWidth(measure, content.distance, distanceSize,
                distanceSingleLine)
                + textHorizontal;
        float directionWidth = textWidth(measure, content.direction, directionSize,
                directionSingleLine)
                + textHorizontal;
        float badgeWidth = widestBadgeRow(measure, content.badgeRows, badgeSize,
                badgePaddingHorizontal, componentGap, roadBadgeSingleLine);
        float auxiliaryWidth = textWidth(measure, content.auxiliary, auxiliarySize,
                auxiliarySingleLine)
                + textHorizontal + Math.max(6f * px, auxiliarySize * .35f) * 2f;

        float primaryWidth;
        if (content.horizontalPrimary) {
            primaryWidth = joinedWidth(componentGap, distanceWidth, badgeWidth, directionWidth);
        } else {
            primaryWidth = Math.max(distanceWidth, Math.max(directionWidth, badgeWidth));
        }
        if (content.hasGraphic) {
            String arrowLayout = options.optString("arrowLayout", "LEFT");
            float arrowMinimum = Math.max(24f * px, distanceSize * 1.35f);
            if ("TOP".equals(arrowLayout) || "BOTTOM".equals(arrowLayout)) {
                primaryWidth = Math.max(primaryWidth, arrowMinimum);
            } else {
                float fraction = clamp(options.optInt("arrowAreaPercent", 38), 10, 75)
                        / 100f;
                float arrowGap = Math.max(0, options.optInt("arrowTextGapPx", 6)) * px;
                // LEFT/RIGHT reserves a percentage of the entire main row. Solve the enclosing
                // width for both regions; treating arrowMinimum as the whole-card minimum would
                // shrink a 38% icon slot to only a few pixels for short distance text.
                float arrowRequired = (arrowMinimum + arrowGap * .5f)
                        / Math.max(.10f, fraction);
                float distanceRequired = (distanceWidth + arrowGap * .5f)
                        / Math.max(.25f, 1f - fraction);
                primaryWidth = Math.max(arrowRequired, distanceRequired);
                primaryWidth = Math.max(primaryWidth,
                        Math.max(directionWidth, badgeWidth));
            }
        }
        float desiredInnerWidth = Math.max(primaryWidth, auxiliaryWidth);
        float maximumWidth = Math.max(1f, maximum.width());
        float minimumWidth = Math.min(maximumWidth,
                Math.max(24f * px, outerLeft + outerRight + 1f));
        float resolvedWidth = autoWidth
                ? Math.max(minimumWidth, Math.min(maximumWidth,
                outerLeft + desiredInnerWidth + outerRight))
                : maximumWidth;
        out.right = out.left + resolvedWidth;

        if (!autoHeight) return;
        float innerWidth = Math.max(1f, resolvedWidth - outerLeft - outerRight);
        float desiredInnerHeight = content.horizontalPrimary
                ? horizontalHeight(options, measure, content, innerWidth, px,
                distanceSize, directionSize, badgeSize, auxiliarySize,
                textHorizontal, textVertical, badgePaddingHorizontal,
                badgePaddingVertical, componentGap, rowGap)
                : stackedHeight(options, measure, content, innerWidth, px,
                distanceSize, directionSize, badgeSize, auxiliarySize,
                textHorizontal, textVertical, badgePaddingHorizontal,
                badgePaddingVertical, rowGap);
        float desiredHeight = Math.max(18f * px,
                outerTop + desiredInnerHeight + outerBottom);
        float availableHeight = Math.max(1f, bottomLimit - maximum.top);
        out.bottom = out.top + Math.min(desiredHeight, availableHeight);
    }

    private static float horizontalHeight(JSONObject options, TextPaint paint, Content content,
                                          float width, float px, float distanceSize,
                                          float directionSize, float badgeSize,
                                          float auxiliarySize, float textHorizontal,
                                          float textVertical, float badgePaddingHorizontal,
                                          float badgePaddingVertical, float componentGap,
                                          float rowGap) {
        float distanceDesired = textWidth(paint, content.distance, distanceSize,
                options.optBoolean("distanceSingleLine", true))
                + textHorizontal;
        float badgeDesired = widestBadgeRow(paint, content.badgeRows, badgeSize,
                badgePaddingHorizontal, componentGap,
                options.optBoolean("roadBadgeSingleLine", true));
        float distanceAvailable = content.distance.isEmpty() ? 0f
                : Math.min(width * .34f, Math.max(1f, distanceDesired));
        float remaining = Math.max(1f, width
                - (distanceAvailable > 0f ? distanceAvailable + componentGap : 0f));
        float badgeAvailable = badgeDesired <= 0f ? 0f
                : Math.min(remaining, badgeDesired);
        remaining = Math.max(1f, remaining
                - (badgeAvailable > 0f ? badgeAvailable + componentGap : 0f));

        float primary = 0f;
        if (!content.distance.isEmpty()) {
            primary = Math.max(primary, textBlockHeight(paint, content.distance,
                    distanceSize, Math.max(1f, distanceAvailable - textHorizontal),
                    options.optBoolean("distanceSingleLine", true)) + textVertical);
        }
        if (badgeAvailable > 0f) {
            primary = Math.max(primary, badgeRowsHeight(paint, content.badgeRows,
                    badgeSize, Math.max(1f, badgeAvailable), badgePaddingHorizontal,
                    badgePaddingVertical, componentGap,
                    options.optBoolean("roadBadgeSingleLine", true)));
        }
        if (!content.direction.isEmpty()) {
            primary = Math.max(primary, textBlockHeight(paint, content.direction,
                    directionSize, Math.max(1f, remaining - textHorizontal),
                    options.optBoolean("directionSingleLine", true)) + textVertical);
        }
        if (content.hasGraphic) primary = Math.max(primary, 24f * px);
        if (primary <= 0f) primary = Math.max(distanceSize, directionSize);

        if (content.auxiliary.isEmpty()) return primary;
        float auxiliary = textBlockHeight(paint, content.auxiliary, auxiliarySize,
                Math.max(1f, width - textHorizontal - Math.max(6f * px,
                        auxiliarySize * .35f) * 2f),
                options.optBoolean("auxiliarySingleLine", true))
                + textVertical + Math.max(4f * px, auxiliarySize * .25f);
        // The independent cluster card reserves 30% for its auxiliary strip. Solve the
        // enclosing height instead of merely adding rows, otherwise either region can clip.
        return Math.max((primary + rowGap) / .70f, auxiliary / .30f);
    }

    private static float stackedHeight(JSONObject options, TextPaint paint, Content content,
                                       float width, float px, float distanceSize,
                                       float directionSize, float badgeSize,
                                       float auxiliarySize, float textHorizontal,
                                       float textVertical, float badgePaddingHorizontal,
                                       float badgePaddingVertical, float rowGap) {
        float distanceAvailable = width;
        float graphicHeight = 0f;
        if (content.hasGraphic) {
            graphicHeight = Math.max(24f * px, distanceSize * 1.35f);
            String layout = options.optString("arrowLayout", "LEFT");
            if (!"TOP".equals(layout) && !"BOTTOM".equals(layout)) {
                float fraction = clamp(options.optInt("arrowAreaPercent", 38), 10, 75)
                        / 100f;
                distanceAvailable = width * (1f - fraction)
                        - Math.max(0, options.optInt("arrowTextGapPx", 6)) * px;
            }
        }
        float distanceHeight = content.distance.isEmpty() ? 0f
                : textBlockHeight(paint, content.distance, distanceSize,
                Math.max(1f, distanceAvailable - textHorizontal),
                options.optBoolean("distanceSingleLine", true)) + textVertical;
        float main = Math.max(distanceHeight, graphicHeight);
        if (content.hasGraphic) {
            String layout = options.optString("arrowLayout", "LEFT");
            if ("TOP".equals(layout) || "BOTTOM".equals(layout)) {
                float fraction = clamp(options.optInt("arrowAreaPercent", 38), 10, 75)
                        / 100f;
                float halfGap = Math.max(0,
                        options.optInt("arrowTextGapPx", 6)) * px * .5f;
                main = Math.max((graphicHeight + halfGap) / fraction,
                        (distanceHeight + halfGap) / Math.max(.25f, 1f - fraction));
            }
        }
        if (main <= 0f) main = Math.max(distanceSize, 18f * px);

        int detailRows = 0;
        float largestDetail = 0f;
        if (!content.direction.isEmpty()) {
            largestDetail = Math.max(largestDetail,
                    textBlockHeight(paint, content.direction, directionSize,
                    Math.max(1f, width - textHorizontal),
                    options.optBoolean("directionSingleLine", true)) + textVertical);
            detailRows++;
        }
        if (!content.badgeRows.isEmpty()) {
            largestDetail = Math.max(largestDetail,
                    badgeRowsHeight(paint, content.badgeRows, badgeSize, width,
                    badgePaddingHorizontal, badgePaddingVertical, rowGap,
                    options.optBoolean("roadBadgeSingleLine", true)));
            detailRows += content.badgeRows.size();
        }
        if (!content.auxiliary.isEmpty()) {
            largestDetail = Math.max(largestDetail,
                    textBlockHeight(paint, content.auxiliary, auxiliarySize,
                    Math.max(1f, width - textHorizontal - Math.max(6f * px,
                            auxiliarySize * .35f) * 2f),
                    options.optBoolean("auxiliarySingleLine", true))
                    + textVertical + Math.max(4f * px, auxiliarySize * .25f));
            detailRows++;
        }
        if (detailRows == 0) return main;
        float mainFraction = clamp(options.optInt("distanceAreaPercent", 56), 20, 80)
                / 100f;
        float requiredAvailable = Math.max(main / mainFraction,
                largestDetail * detailRows / Math.max(.20f, 1f - mainFraction));
        return requiredAvailable + detailRows * rowGap;
    }

    private static float badgeRowsHeight(TextPaint paint, List<List<String>> rows,
                                         float size, float availableWidth,
                                         float horizontalPadding, float verticalPadding,
                                         float gap, boolean singleLine) {
        float result = 0f;
        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) continue;
            float rowHeight = 0f;
            float eachWidth = Math.max(1f, (availableWidth
                    - gap * Math.max(0, row.size() - 1)) / Math.max(1, row.size()));
            for (String value : row) {
                rowHeight = Math.max(rowHeight, textBlockHeight(paint, value, size,
                        Math.max(1f, eachWidth - horizontalPadding * 2f), singleLine)
                        + verticalPadding * 2f);
            }
            result = Math.max(result, rowHeight);
        }
        return result;
    }

    private static float widestBadgeRow(TextPaint paint, List<List<String>> rows,
                                        float size, float padding, float gap,
                                        boolean singleLine) {
        float widest = 0f;
        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) continue;
            float width = gap * Math.max(0, row.size() - 1);
            for (String value : row) {
                width += textWidth(paint, value, size, singleLine) + padding * 2f;
            }
            widest = Math.max(widest, width);
        }
        return widest;
    }

    private static float joinedWidth(float gap, float... values) {
        float result = 0f;
        int count = 0;
        for (float value : values) {
            if (value <= 0f) continue;
            result += value;
            count++;
        }
        return result + gap * Math.max(0, count - 1);
    }

    private static float textBlockHeight(TextPaint paint, String value, float size,
                                         float width, boolean singleLine) {
        if (value == null || value.trim().isEmpty()) return 0f;
        paint.setTextSize(size);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float oneLine = Math.max(1f, metrics.descent - metrics.ascent);
        if (singleLine) return oneLine;
        int layoutWidth = Math.max(1, Math.round(width));
        StaticLayout layout = StaticLayout.Builder.obtain(
                        value, 0, value.length(), paint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(MAX_WRAPPED_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(layoutWidth)
                .build();
        return Math.max(oneLine, layout.getHeight());
    }

    private static float textWidth(TextPaint paint, String value, float size,
                                   boolean singleLine) {
        if (value == null || value.trim().isEmpty()) return 0f;
        paint.setTextSize(size);
        if (singleLine) {
            return paint.measureText(value.replace('\n', ' ').replace('\r', ' '));
        }
        float widest = 0f;
        int start = 0;
        for (int index = 0; index <= value.length(); index++) {
            boolean end = index == value.length();
            char character = end ? '\0' : value.charAt(index);
            if (!end && character != '\n' && character != '\r') continue;
            widest = Math.max(widest, paint.measureText(value, start, index));
            if (character == '\r' && index + 1 < value.length()
                    && value.charAt(index + 1) == '\n') index++;
            start = index + 1;
        }
        return widest;
    }

    private static boolean hasContent(Content content) {
        if (!content.distance.trim().isEmpty() || !content.direction.trim().isEmpty()
                || !content.auxiliary.trim().isEmpty() || content.hasGraphic) return true;
        for (List<String> row : content.badgeRows) {
            if (row != null && !row.isEmpty()) return true;
        }
        return false;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private ManeuverCardAutoSizer() {}
}
