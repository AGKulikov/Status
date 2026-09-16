/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Window health, never tile/readback readiness, decides whether to retain a HUD owner. */
final class HudOwnerReconcilePolicy {
    static final long ATTACH_GRACE_MS = 2_000L;
    private HudOwnerReconcilePolicy() {}

    static boolean retain(boolean sameDisplay, boolean hasOwner, boolean usable,
                          long ownerAgeMs, boolean quickBoot) {
        if (!sameDisplay || !hasOwner || ownerAgeMs < 0L) return false;
        // An immediate duplicate boot command must not tear down the window it just created.
        if (ownerAgeMs < ATTACH_GRACE_MS) return true;
        return usable && !quickBoot;
    }
}
