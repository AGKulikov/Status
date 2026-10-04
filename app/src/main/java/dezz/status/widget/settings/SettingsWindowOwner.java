/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;

/** A non-Activity editor owns its child windows as well as its root window. */
public interface SettingsWindowOwner {
    void ownDialog(Dialog dialog);
    boolean windowOwnerDestroyed();

    static SettingsWindowOwner find(Context context) {
        while (context != null) {
            if (context instanceof SettingsWindowOwner) return (SettingsWindowOwner) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    static <T extends Dialog> T attach(Context context, T dialog) {
        SettingsWindowOwner owner = find(context);
        if (owner != null) owner.ownDialog(dialog);
        return dialog;
    }
}
