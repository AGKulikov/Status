/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

/** Scalar defaults and in-place migration rules for PHONE values shown in Information tiles. */
public final class PhoneInformationSourcePolicy {
    private PhoneInformationSourcePolicy() {
    }

    /** Lists and transport-only identity objects are not useful as one-line Information values. */
    public static boolean selectable(@NonNull String resourceId) {
        return !"diagnostics.device".equals(resourceId)
                && !"notifications.items".equals(resourceId);
    }

    /**
     * Uses an explicit reserved prefix for fields inside the connector's primary object. This
     * avoids reinterpreting legacy attribute paths such as {@code value.name}.
     */
    @NonNull
    public static String valuePath(@NonNull String resourceId) {
        switch (resourceId) {
            case "notifications.latest":
                return "@value.app_name";
            case "messages.latest":
                return "@value.display";
            case "diagnostics.last_app":
                return "@value.name";
            default:
                return "";
        }
    }

    /** Upgrades HA1080 tiles whose object binding predated scalar phone defaults. */
    @Nullable
    public static SourceBinding migrate(@Nullable SourceBinding binding) {
        if (binding == null || binding.connectorType != ConnectorType.PHONE
                || !binding.valuePath.isEmpty()) {
            return binding;
        }
        String path = valuePath(binding.resourceId);
        if (path.isEmpty()) return binding;
        return new SourceBinding(binding.connectorType, binding.connectorId,
                binding.resourceId, path, binding.presentation, binding.unitSuffix);
    }

    @Nullable
    public static Object displayValue(@NonNull ConnectorValue value) {
        String path = valuePath(value.resourceId);
        return value.resolveValue(path);
    }
}
