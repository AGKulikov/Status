/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.Nullable;

/** Unknown/disconnected lock state never leaks notifications in lock-only mode. */
public final class PhoneNotificationLockPolicy {
    private PhoneNotificationLockPolicy() { }

    public static boolean mayPresent(boolean onlyWhenLocked, @Nullable Boolean phoneLocked) {
        return !onlyWhenLocked || Boolean.TRUE.equals(phoneLocked);
    }
}
