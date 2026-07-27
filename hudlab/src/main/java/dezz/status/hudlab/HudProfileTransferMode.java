/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

/**
 * Validated values accepted by ECARX {@code DispModSetgReq}.
 *
 * <p>The vendor wrapper rejects every other value before it reaches CB33278. Raw diagnostic
 * writes deliberately bypass this validator and must therefore use a separate controller path.</p>
 */
final class HudProfileTransferMode {
    static final int MIN_SDK_MODE = 0;
    static final int MAX_SDK_MODE = 3;
    static final int RAW_INVALID_SENTINEL = -1;

    private HudProfileTransferMode() {
    }

    static boolean isSdkMode(int value) {
        return value >= MIN_SDK_MODE && value <= MAX_SDK_MODE;
    }

    static int requireSdkMode(int value) {
        if (!isSdkMode(value)) {
            throw new IllegalArgumentException(
                    "ProfileTransfer mode должен быть 0…3, получено " + value);
        }
        return value;
    }
}
