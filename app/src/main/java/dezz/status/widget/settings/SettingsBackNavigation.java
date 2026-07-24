/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import dezz.status.widget.R;
import dezz.status.widget.WidgetService;

/**
 * Adds one consistent, visible back control to legacy programmatic settings screens.
 *
 * <p>Most of those screens use a NoActionBar theme, so {@code setTitle()} alone never rendered
 * navigation.  Rewriting all large live editors into one layout would risk their preview and
 * autosave behavior; this small chrome reserves a real top row and keeps the underlying editor
 * untouched.  It also accounts for the app's own status-row overlay.</p>
 */
public final class SettingsBackNavigation {
    private interface SafeInsetListener {
        void onExtraTopChanged(int extraTop);
    }

    private SettingsBackNavigation() {
    }

    public static void install(@NonNull AppCompatActivity activity,
                               @NonNull View content) {
        View existing = activity.findViewById(R.id.settings_back_button);
        if (existing != null) {
            ViewGroup parent = (ViewGroup) existing.getParent();
            if (parent != null) parent.removeView(existing);
        }

        Integer taggedBase = (Integer) content.getTag(R.id.settings_back_base_padding);
        int baseTop = taggedBase == null ? content.getPaddingTop() : taggedBase;
        content.setTag(R.id.settings_back_base_padding, baseTop);

        MaterialButton back = new MaterialButton(activity);
        back.setId(R.id.settings_back_button);
        back.setAllCaps(false);
        back.setText("Назад");
        back.setTextSize(15);
        back.setGravity(Gravity.CENTER);
        back.setIconResource(R.drawable.ic_arrow_back);
        back.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.settings_accent)));
        back.setTextColor(ContextCompat.getColor(activity, R.color.settings_accent));
        back.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.settings_group_background)));
        back.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.settings_separator)));
        back.setStrokeWidth(dp(activity, 1));
        back.setCornerRadius(dp(activity, 14));
        back.setInsetTop(0);
        back.setInsetBottom(0);
        back.setContentDescription("Назад");
        back.setElevation(dp(activity, 4));
        back.setOnClickListener(view -> activity.finish());

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(activity, 116), dp(activity, 48), Gravity.TOP | Gravity.START);
        buttonParams.leftMargin = dp(activity, 14);
        activity.addContentView(back, buttonParams);

        trackSafeTop(activity, content, baseTop, dp(activity, 64), extra -> {
            ViewGroup.LayoutParams raw = back.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                params.topMargin = dp(activity, 8) + extra;
                back.setLayoutParams(params);
            }
        });
    }

    /**
     * Keeps an Activity's own toolbar/back row below the app status overlay without adding a
     * second Back button. Existing system-inset listeners remain authoritative and are composed
     * with the overlay inset rather than overwritten.
     */
    public static void applySafeTopInset(@NonNull AppCompatActivity activity,
                                         @NonNull View content) {
        trackSafeTop(activity, content, content.getPaddingTop(), 0, null);
    }

    private static void trackSafeTop(@NonNull AppCompatActivity activity,
                                     @NonNull View content,
                                     int initialBaseTop,
                                     int reservedTop,
                                     SafeInsetListener listener) {
        final int[] baseTop = {initialBaseTop};
        final int[] lastAppliedTop = {Integer.MIN_VALUE};
        final int[] appliedExtra = {Integer.MIN_VALUE};
        final Runnable[] updater = new Runnable[1];
        updater[0] = () -> {
            if (!content.isAttachedToWindow() || activity.isFinishing()
                    || activity.isDestroyed()) {
                return;
            }
            int observedTop = content.getPaddingTop();
            if (lastAppliedTop[0] == Integer.MIN_VALUE
                    ? observedTop != baseTop[0] : observedTop != lastAppliedTop[0]) {
                // Edge-to-edge screens may apply their system-bar padding after this helper is
                // installed. Treat that external value as the new baseline, then add only the
                // part of our own overlay that is not already covered by the system inset.
                baseTop[0] = observedTop;
            }
            int systemTop = systemTopInset(content);
            int extra = Math.max(0, statusOverlayHeight() - systemTop);
            int desiredTop = baseTop[0] + reservedTop + extra;
            if (desiredTop != lastAppliedTop[0]) {
                lastAppliedTop[0] = desiredTop;
                content.setPadding(content.getPaddingLeft(), desiredTop,
                        content.getPaddingRight(), content.getPaddingBottom());
            }
            if (extra != appliedExtra[0]) {
                appliedExtra[0] = extra;
                if (listener != null) listener.onExtraTopChanged(extra);
            }
            // The overlay can start after this Activity or change height when its content changes.
            content.postDelayed(updater[0], 750L);
        };
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.removeCallbacks(updater[0]);
                view.post(updater[0]);
            }

            @Override public void onViewDetachedFromWindow(View view) {
                view.removeCallbacks(updater[0]);
            }
        });
        content.post(updater[0]);
    }

    private static int systemTopInset(@NonNull View content) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(content);
        if (insets == null) return 0;
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
        return Math.max(0, bars.top);
    }

    private static int statusOverlayHeight() {
        WidgetService service = WidgetService.getInstance();
        return service == null ? 0 : service.getStatusBarOverlayHeight();
    }

    private static int dp(@NonNull AppCompatActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
