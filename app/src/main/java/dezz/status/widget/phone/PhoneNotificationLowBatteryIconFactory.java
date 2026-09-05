/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import dezz.status.widget.R;

/** iOS-style red application tile carrying the real low-battery glyph. */
public final class PhoneNotificationLowBatteryIconFactory {
    private PhoneNotificationLowBatteryIconFactory() {
    }

    @NonNull
    public static Drawable create(@NonNull Context context, int iconSize) {
        Drawable source = ContextCompat.getDrawable(context, R.drawable.ic_status_iphone_battery);
        if (source == null) return background();
        Drawable glyph = DrawableCompat.wrap(source.mutate());
        DrawableCompat.setTint(glyph, 0xFFFFFFFF);
        LayerDrawable result = new LayerDrawable(new Drawable[]{
                background(), glyph
        });
        int inset = Math.max(1, Math.round(Math.max(1, iconSize) * 0.20f));
        result.setLayerInset(1, inset, inset, inset, inset);
        return result;
    }

    @NonNull
    private static Drawable background() {
        GradientDrawable value = new GradientDrawable();
        value.setColor(0xFFFF453A);
        value.setShape(GradientDrawable.RECTANGLE);
        // The published bitmap applies the user's exact continuous corner mask afterwards. This
        // inner radius only removes the obvious square before that mask is available on frame 1.
        value.setCornerRadius(12f);
        return value;
    }
}
