/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.os.SystemClock;
import java.util.function.BooleanSupplier;

/** Preserve the original deadline across the action lane -> MAIN boundary. */
public final class ButtonActionDeadline {
    private static final ThreadLocal<ButtonActionDeadline> active = new ThreadLocal<>();
    public final long expiresAt;
    private final BooleanSupplier owner;
    private ButtonActionDeadline(long expiresAt, BooleanSupplier owner) {
        this.expiresAt = expiresAt; this.owner = owner;
    }
    public static ButtonActionDeadline current() {
        ButtonActionDeadline value = active.get();
        return value != null ? value : new ButtonActionDeadline(SystemClock.uptimeMillis() + 750, () -> true);
    }
    public boolean valid() { return SystemClock.uptimeMillis() <= expiresAt && owner.getAsBoolean(); }
    public static void run(long expiresAt, BooleanSupplier owner, Runnable action) {
        ButtonActionDeadline previous = active.get();
        ButtonActionDeadline value = new ButtonActionDeadline(expiresAt, owner);
        active.set(value);
        try { if (value.valid()) action.run(); }
        finally { if (previous == null) active.remove(); else active.set(previous); }
    }
}
