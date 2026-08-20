/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import dezz.status.widget.R;

/** A square iOS-style Messages sample whose visible corners are controlled by the editor. */
public final class PhoneNotificationPreviewIconFactory {
    private PhoneNotificationPreviewIconFactory() {
    }

    @NonNull
    public static Drawable create(@NonNull Context context, int iconSize) {
        Drawable glyph = ContextCompat.getDrawable(context, R.drawable.ic_phone_app_messages);
        if (glyph == null) return new ColorDrawable(0xFF34C759);
        LayerDrawable result = new LayerDrawable(new Drawable[]{
                new ColorDrawable(0xFF34C759), glyph
        });
        int inset = Math.max(1, Math.round(Math.max(1, iconSize) * 0.20f));
        result.setLayerInset(1, inset, inset, inset, inset);
        return result;
    }
}
