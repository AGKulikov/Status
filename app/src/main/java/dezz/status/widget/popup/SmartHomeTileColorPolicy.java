/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;

/** Keeps a smart-home tile's icon, device name and live status on one state colour. */
public final class SmartHomeTileColorPolicy {
    private SmartHomeTileColorPolicy() {
    }

    public static boolean applies(@Nullable SourceBinding binding) {
        if (binding == null || !binding.isBound()) return false;
        return binding.connectorType == ConnectorType.HOME_ASSISTANT
                || binding.connectorType == ConnectorType.SPRUTHUB
                || binding.connectorType == ConnectorType.MQTT;
    }

    @NonNull
    public static String contentColor(@Nullable SourceBinding binding,
                                      @Nullable String configuredColor,
                                      @Nullable String stateColor) {
        String configured = configuredColor == null ? "" : configuredColor.trim();
        String state = stateColor == null ? "" : stateColor.trim();
        if (applies(binding) && !state.isEmpty()) return state;
        return configured.isEmpty() ? "#FFFFFFFF" : configured;
    }
}
