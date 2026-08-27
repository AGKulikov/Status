/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Fail-safe gate for hiding the OEM HUD behind Natro's direct surface. */
final class HudStockMaskPolicy {
    private HudStockMaskPolicy() {}

    static boolean shouldHideStockCar(boolean maskRequested,
                                      boolean customFrameReady,
                                      boolean customContentPresent) {
        return maskRequested && customFrameReady && customContentPresent;
    }
}
