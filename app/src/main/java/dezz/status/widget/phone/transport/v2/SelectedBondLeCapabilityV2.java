/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public final class SelectedBondLeCapabilityV2 {
    static final int DEVICE_TYPE_CLASSIC = 1;
    static final int DEVICE_TYPE_DUAL = 3;
    static final int DEVICE_TYPE_LE = 2;
    static final int DEVICE_TYPE_UNKNOWN = 0;

    public enum Result {
        LE_CAPABLE,
        CLASSIC_ONLY,
        UNKNOWN
    }

    private SelectedBondLeCapabilityV2() {
    }

    public static dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result classify(int i) {
        if (i == 2 || i == 3) {
            return dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.LE_CAPABLE;
        }
        if (i == 1) {
            return dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.CLASSIC_ONLY;
        }
        return dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.UNKNOWN;
    }

    public static java.lang.String diagnostic(dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result result) {
        if (result == dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.LE_CAPABLE) {
            return "selected_bond transport=LE_CAPABLE";
        }
        if (result == dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.CLASSIC_ONLY) {
            return "selected_bond transport=CLASSIC_ONLY, direct_le=false";
        }
        return "selected_bond transport=UNKNOWN, direct_le=false";
    }

    public static java.lang.String terminalDetail(dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result result) {
        if (result == dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2.Result.CLASSIC_ONLY) {
            return "ROUTE_A_CLASSIC_ONLY_BOND: selected iPhone bond exposes Classic only; exact-bond LE identity/keys are absent, so directed Route-A GATT is blocked without radio, cache, or pairing mutation";
        }
        return "ROUTE_A_LE_CAPABILITY_UNKNOWN: selected iPhone bond has no proven LE-capable facade; directed Route-A GATT is blocked without identity fallback";
    }
}
