/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;

/** A staged visual colour override. Null keeps the source artwork's colour. */
public final class OptionalColorButton extends MaterialButton {
    @Nullable private String value;

    public OptionalColorButton(Activity activity, LinearLayout parent,
                               String title, @Nullable String initial) {
        super(activity);
        value = initial;
        setAllCaps(false);
        update(title, initial);
        setOnClickListener(view -> AppleColorPickerDialog.show(activity, title, value,
                AppleColorPickerDialog.Options.inheritable(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String selected) {
                        update(title, selected);
                    }
                    @Override public void onSelected(@Nullable String selected) {
                        update(title, selected);
                    }
                }));
        parent.addView(this, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Nullable public String value() { return value; }

    private void update(String title, @Nullable String selected) {
        value = selected;
        AppleColorPickerDialog.decorateButton(this, title, value, "Штатный цвет");
    }
}
