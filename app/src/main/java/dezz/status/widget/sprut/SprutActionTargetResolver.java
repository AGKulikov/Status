/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;

import java.util.Locale;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.sprut.preset.SprutPopupPreset;
import dezz.status.widget.sprut.preset.SprutPopupPresetEngine;

/**
 * Resolves an interactive Sprut action against the authoritative catalog.
 *
 * <p>Early popup configurations used the displayed characteristic as the command target.  That
 * is correct for a switch's writable {@code On}, but not for covers: a tile displays the
 * read-only {@code CurrentDoorState}/{@code CurrentPosition} and must write the sibling
 * {@code TargetDoorState}/{@code TargetPosition}.  This resolver keeps those already-saved tiles
 * working while still refusing arbitrary read-only characteristics.</p>
 */
public final class SprutActionTargetResolver {
    private SprutActionTargetResolver() {}

    @NonNull
    public static Resolved resolve(@NonNull SprutCatalog catalog,
                                   @NonNull ActionBinding requested) {
        if (requested.connectorType != ConnectorType.SPRUTHUB || !requested.isBound()) {
            throw new IllegalArgumentException("Not a Sprut.hub action");
        }
        SprutPath requestedPath = SprutPath.parse(requested.resourceId);
        SprutCatalog.Characteristic requestedCharacteristic = catalog.find(requestedPath);
        if (requestedCharacteristic == null) {
            throw new IllegalArgumentException("Characteristic does not exist: " + requestedPath);
        }
        SprutCatalog.Accessory accessory = catalog.findAccessory(requestedPath.accessoryId());
        SprutCatalog.Service service = catalog.findService(requestedPath.accessoryId(),
                requestedPath.serviceId());
        SprutPopupPreset preset = accessory == null || service == null ? null
                : new SprutPopupPresetEngine().recommend(accessory, service);
        if (requestedCharacteristic.writable()) {
            // Even a correctly saved TargetPosition binding needs the readable current state to
            // turn a user-facing "toggle" into a deterministic open/close endpoint.
            if (preset != null && preset.presentation() == SprutPopupPreset.Presentation.COVER
                    && preset.actionCharacteristicPath().isPresent()
                    && preset.actionCharacteristicPath().get().equals(requestedPath)
                    && preset.primaryCharacteristicPath().isPresent()) {
                SprutCatalog.Characteristic state = catalog.find(
                        preset.primaryCharacteristicPath().get());
                if (state != null) return resolveCover(requested, preset, state,
                        requestedCharacteristic);
            }
            return new Resolved(requested, requestedCharacteristic);
        }

        if (accessory == null || service == null) {
            throw notWritable(requestedPath);
        }
        // Automatic redirection is intentionally restricted to typed cover/gate services.  For
        // an arbitrary sensor, silently picking another writable value from the same service
        // would be surprising and potentially unsafe.
        if (preset.presentation() != SprutPopupPreset.Presentation.COVER
                || !preset.actionCharacteristicPath().isPresent()
                || !preset.primaryCharacteristicPath().isPresent()
                || (!preset.primaryCharacteristicPath().get().equals(requestedPath)
                && !isCurrentCoverState(requestedCharacteristic))) {
            throw notWritable(requestedPath);
        }
        SprutCatalog.Characteristic target = catalog.find(
                preset.actionCharacteristicPath().get());
        if (target == null || !target.writable()) throw notWritable(requestedPath);

        return resolveCover(requested, preset, requestedCharacteristic, target);
    }

    /** Allows an explicitly selected alternate current-state value, but never a generic sensor
     * or writable vendor option from the same service. */
    private static boolean isCurrentCoverState(SprutCatalog.Characteristic characteristic) {
        String type = normalize(characteristic.type() + " " + characteristic.name());
        return type.contains("currentdoorstate") || type.contains("currentposition")
                || type.contains("positionstate") || type.contains("текущийрежимдвери")
                || type.contains("текущеесостояниедвери")
                || type.contains("текущаяпозици") || type.contains("текущееположени");
    }

    private static Resolved resolveCover(ActionBinding requested, SprutPopupPreset preset,
                                         SprutCatalog.Characteristic state,
                                         SprutCatalog.Characteristic target) {
        String operation = requested.operation;
        String payload = requested.payload;
        if (ActionBinding.OPERATION_TOGGLE.equals(operation)) {
            // A CurrentPosition tile cannot safely TOGGLE TargetPosition using the target's
            // arbitrary intermediate value.  Convert the user's intent into an explicit endpoint
            // based on the displayed authoritative state.
            boolean shouldOpen = shouldOpenAfterToggle(state);
            Object desired = shouldOpen ? openValue(preset, target) : closedValue(preset,
                    target);
            operation = ActionBinding.OPERATION_SET;
            payload = SprutActionValue.encodePrimitive(desired);
        } else if (ActionBinding.OPERATION_SET.equals(operation)) {
            Boolean directional = directionalBoolean(payload);
            if (directional != null) {
                Object desired = directional ? openValue(preset, target)
                        : closedValue(preset, target);
                payload = SprutActionValue.encodePrimitive(desired);
            }
        }

        ActionBinding resolved = new ActionBinding(ConnectorType.SPRUTHUB,
                requested.connectorId, target.path().stableId(), operation, payload);
        return new Resolved(resolved, target);
    }

    private static boolean shouldOpenAfterToggle(SprutCatalog.Characteristic source) {
        Object value = source.currentValue();
        if (value == null) {
            throw new IllegalArgumentException("Current cover state is unavailable");
        }
        String type = normalize(source.type() + " " + source.name());
        // HomeKit/Sprut door states: 0=open, 1=closed, 2=opening, 3=closing, 4=stopped.
        // A press while the door is moving deliberately reverses its direction.
        if (type.contains("doorstate") || type.contains("режимдвери")
                || type.contains("состояниедвери")) {
            return equivalent(value, 1) || equivalent(value, 3);
        }
        if (type.contains("position") || type.contains("позици")
                || type.contains("положени")) {
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("Current cover position is not numeric");
            }
            Number minimum = source.minValue();
            double closed = minimum == null ? 0d : minimum.doubleValue();
            return Double.compare(((Number) value).doubleValue(), closed) == 0;
        }
        if (value instanceof Boolean) return !((Boolean) value);
        return equivalent(value, 0) || "closed".equalsIgnoreCase(String.valueOf(value));
    }

    private static Object openValue(SprutPopupPreset preset,
                                    SprutCatalog.Characteristic target) {
        if (preset.defaultActionPayload().isPresent()) {
            return preset.defaultActionPayload().get();
        }
        Object labelled = labelledValue(target, true);
        if (labelled != null) return labelled;
        String type = normalize(target.type() + " " + target.name());
        if (type.contains("targetposition") || type.contains("целеваяпозици")
                || type.contains("целевоеположени")) {
            return target.maxValue() == null ? 100 : target.maxValue();
        }
        return 0;
    }

    private static Object closedValue(SprutPopupPreset preset,
                                      SprutCatalog.Characteristic target) {
        Object labelled = labelledValue(target, false);
        if (labelled != null) return labelled;
        Object open = openValue(preset, target);
        if (target.validValues().size() == 2) {
            Object first = target.validValues().get(0).value();
            Object second = target.validValues().get(1).value();
            if (equivalent(open, first)) return second;
            if (equivalent(open, second)) return first;
        }
        String type = normalize(target.type() + " " + target.name());
        if (type.contains("targetposition") || type.contains("целеваяпозици")
                || type.contains("целевоеположени")) {
            return target.minValue() == null ? 0 : target.minValue();
        }
        return 1;
    }

    private static Object labelledValue(SprutCatalog.Characteristic target, boolean open) {
        for (SprutCatalog.ValidValue candidate : target.validValues()) {
            String label = normalize(candidate.key() + " " + candidate.name());
            if (open) {
                if ((label.contains("open") || label.contains("откры"))
                        && !label.contains("closed")) return candidate.value();
            } else if (label.contains("closed") || label.contains("close")
                    || label.contains("закры")) {
                return candidate.value();
            }
        }
        return null;
    }

    private static Boolean directionalBoolean(String payload) {
        String value = payload == null ? "" : payload.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        return null;
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(),
                    ((Number) right).doubleValue()) == 0;
        }
        return left != null && right != null
                && String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-zа-я0-9]", "");
    }

    private static IllegalArgumentException notWritable(SprutPath path) {
        return new IllegalArgumentException("Characteristic is not writable: " + path);
    }

    public static final class Resolved {
        private final ActionBinding binding;
        private final SprutCatalog.Characteristic characteristic;

        private Resolved(ActionBinding binding, SprutCatalog.Characteristic characteristic) {
            this.binding = binding;
            this.characteristic = characteristic;
        }

        @NonNull public ActionBinding binding() { return binding; }

        @NonNull public SprutCatalog.Characteristic characteristic() { return characteristic; }
    }
}
