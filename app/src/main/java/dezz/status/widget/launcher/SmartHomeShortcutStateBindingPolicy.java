/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutPath;
import dezz.status.widget.sprut.preset.SprutPopupPreset;
import dezz.status.widget.sprut.preset.SprutPopupPresetEngine;

/**
 * Selects the live value that should drive a smart-home HOME tile independently from the
 * characteristic that receives its command.
 *
 * <p>Sprut.hub covers expose a writable target and a separate authoritative current state.
 * Using {@code TargetDoorState} for both makes a moving gate look fully open/closed as soon as
 * the command is accepted. This policy keeps explicitly selected display sources intact while
 * repairing the old automatic command-as-state binding.</p>
 */
public final class SmartHomeShortcutStateBindingPolicy {
    private SmartHomeShortcutStateBindingPolicy() {}

    @Nullable
    public static SourceBinding resolve(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                        @Nullable IntentActionRule rule,
                                        @Nullable SprutCatalog catalog) {
        SourceBinding configured = SmartHomeShortcutStatePolicy.bindingFor(shortcut, rule);
        if (rule == null) return configured;
        return preferSprutPrimary(rule.command, configured, catalog);
    }

    /**
     * Returns the readable service characteristic when the saved source was automatically copied
     * from the command. Explicitly bound sources from a different characteristic are preserved.
     */
    @Nullable
    public static SourceBinding preferSprutPrimary(@NonNull ActionBinding command,
                                                   @Nullable SourceBinding configured,
                                                   @Nullable SprutCatalog catalog) {
        if (catalog == null || command.connectorType != ConnectorType.SPRUTHUB
                || !command.isBound()) return configured;
        final SprutPath commandPath;
        try {
            commandPath = SprutPath.parse(command.resourceId);
        } catch (IllegalArgumentException invalid) {
            return configured;
        }
        SprutCatalog.Accessory accessory = catalog.findAccessory(commandPath.accessoryId());
        SprutCatalog.Service service = catalog.findService(commandPath.accessoryId(),
                commandPath.serviceId());
        if (accessory == null || service == null) return configured;

        final SprutPopupPreset preset;
        try {
            preset = new SprutPopupPresetEngine().recommend(accessory, service);
        } catch (IllegalArgumentException unsupported) {
            return configured;
        }
        if (!preset.primaryCharacteristicPath().isPresent()) return configured;
        SprutPath primary = preset.primaryCharacteristicPath().get();
        SprutCatalog.Characteristic primaryValue = catalog.find(primary);
        if (primaryValue == null || !primaryValue.readable()) return configured;

        String actionPath = preset.actionCharacteristicPath().isPresent()
                ? preset.actionCharacteristicPath().get().stableId() : command.resourceId;
        // A readable Target* value is still only the requested endpoint. If a cover service has
        // no separate Current* characteristic, showing the target as confirmed physical state is
        // worse than showing an unknown state.
        String primaryType = normalize(primaryValue.type() + " " + primaryValue.name());
        if (preset.presentation() == SprutPopupPreset.Presentation.COVER
                && primary.stableId().equals(actionPath)
                && (primaryType.contains("targetdoorstate")
                || primaryType.contains("targetposition")
                || primaryType.contains("целевойрежимдвери")
                || primaryType.contains("целевоесостояниедвери")
                || primaryType.contains("целеваяпозици")
                || primaryType.contains("целевоеположени"))) {
            return null;
        }
        boolean automatic = configured == null || !configured.isBound()
                || (configured.connectorType == ConnectorType.SPRUTHUB
                && configured.connectorId.equals(command.connectorId)
                && (configured.resourceId.equals(command.resourceId)
                || configured.resourceId.equals(actionPath)));
        if (!automatic) return configured;

        return new SourceBinding(ConnectorType.SPRUTHUB, command.connectorId,
                primary.stableId(), "", presentation(preset.presentation()), "");
    }

    @NonNull
    private static String presentation(SprutPopupPreset.Presentation value) {
        switch (value) {
            case COVER: return SourceBinding.PRESENTATION_COVER;
            case BOOLEAN: return SourceBinding.PRESENTATION_BOOLEAN;
            case TEMPERATURE: return SourceBinding.PRESENTATION_TEMPERATURE;
            case RAW: return SourceBinding.PRESENTATION_RAW;
            case AUTO:
            default: return SourceBinding.PRESENTATION_AUTO;
        }
    }

    @NonNull
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-zа-я0-9]", "");
    }
}
