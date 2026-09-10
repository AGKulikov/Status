/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors the live semantic colours of Navigator's own alternative-route shutter.
 *
 * <p>The exact 30.3.0 view may put both deltas into one spannable TextView or into separate
 * TextViews. Reading the currently rendered colour keeps Natro aligned with day/night themes and
 * avoids treating a colour sampled from a sun-distorted photograph as a source constant.</p>
 */
final class StockAlternativePalette {
    private static final int FALLBACK_NEGATIVE = 0xFF52D77A;
    private static final int FALLBACK_POSITIVE = 0xFFFF7F73;
    private static final Pattern NEGATIVE = Pattern.compile(
            "[\\-\\u2212]\\s*\\d+(?:[.,]\\d+)?\\s*(?:мин(?:\\.|ут[аы]?)?|min|км|km|м|m)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern POSITIVE = Pattern.compile(
            "\\+\\s*\\d+(?:[.,]\\d+)?\\s*(?:мин(?:\\.|ут[аы]?)?|min|км|km|м|m)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static volatile int negativeColor = FALLBACK_NEGATIVE;
    private static volatile int positiveColor = FALLBACK_POSITIVE;
    private static volatile long version;

    private StockAlternativePalette() {}

    static int negativeColor() {
        return negativeColor;
    }

    static int positiveColor() {
        return positiveColor;
    }

    static long version() {
        return version;
    }

    /** Must run on Navigator's UI thread because Spans and current ColorStateList are View state. */
    static boolean capture(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) {
            return false;
        }
        View root = activity.getWindow().getDecorView();
        if (root == null) return false;
        Integer nextNegative = null;
        Integer nextPositive = null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) continue;
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                CharSequence text = textView.getText();
                if (text != null && text.length() > 0) {
                    Matcher negative = NEGATIVE.matcher(text);
                    while (negative.find()) {
                        Integer color = colorAt(textView, text, negative.start(), negative.end());
                        if (color != null) nextNegative = color;
                    }
                    Matcher positive = POSITIVE.matcher(text);
                    while (positive.find()) {
                        Integer color = colorAt(textView, text, positive.start(), positive.end());
                        if (color != null) nextPositive = color;
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        boolean changed = false;
        if (nextNegative != null && nextNegative != negativeColor) {
            negativeColor = nextNegative;
            changed = true;
        }
        if (nextPositive != null && nextPositive != positiveColor) {
            positiveColor = nextPositive;
            changed = true;
        }
        if (changed) version++;
        // Keep discovery cadence until both semantic colours have been observed. A stock layout
        // may briefly render only one of the two signed deltas while its shutter is animating.
        return nextNegative != null && nextPositive != null;
    }

    private static Integer colorAt(TextView view, CharSequence text, int start, int end) {
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            ForegroundColorSpan[] foreground = spanned.getSpans(
                    start, end, ForegroundColorSpan.class);
            if (foreground.length > 0) return foreground[foreground.length - 1].getForegroundColor();
            TextAppearanceSpan[] appearances = spanned.getSpans(
                    start, end, TextAppearanceSpan.class);
            for (int index = appearances.length - 1; index >= 0; index--) {
                ColorStateList colors = appearances[index].getTextColor();
                if (colors != null) return colors.getColorForState(
                        view.getDrawableState(), colors.getDefaultColor());
            }
        }
        // A whole short TextView is safe. Do not reuse its default colour for one substring of a
        // multi-colour sentence when the stock spans are temporarily unavailable.
        String whole = text.toString().trim().toLowerCase(Locale.ROOT);
        if (whole.length() <= end - start + 4) return view.getCurrentTextColor();
        return null;
    }
}
