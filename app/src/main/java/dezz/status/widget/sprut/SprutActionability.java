/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;

/** Pure actionability policy shared by full-catalog Sprut.hub pickers. */
public final class SprutActionability {
    private SprutActionability() {}

    public static boolean canControl(@NonNull SprutCatalog.Accessory accessory) {
        for (SprutCatalog.Service service : accessory.services()) {
            if (canControl(service)) return true;
        }
        return false;
    }

    public static boolean canControl(@NonNull SprutCatalog.Service service) {
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (canControl(characteristic)) return true;
        }
        return false;
    }

    public static boolean canControl(@NonNull SprutCatalog.Characteristic characteristic) {
        return characteristic.writable();
    }
}
