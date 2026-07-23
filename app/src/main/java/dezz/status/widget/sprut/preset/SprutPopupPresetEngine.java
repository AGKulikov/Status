/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut.preset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.sprut.SprutCatalog;

/** Deterministic popup defaults derived only from a Sprut.hub catalog snapshot. */
public final class SprutPopupPresetEngine {
    private static final String WHITE = "#FFFFFFFF";
    private static final String GREEN = "#FF4CAF50";
    private static final String ORANGE = "#FFFF9800";
    private static final String RED = "#FFF44336";
    private static final String YELLOW = "#FFFFC107";
    private static final String BLUE = "#FF2196F3";
    private static final String GRAY = "#FF9E9E9E";

    /** Returns one preset for every non-system service that has at least one characteristic. */
    public List<SprutPopupPreset> recommendAll(SprutCatalog.Accessory accessory) {
        Objects.requireNonNull(accessory, "accessory");
        List<SprutPopupPreset> result = new ArrayList<>();
        for (SprutCatalog.Service service : accessory.services()) {
            if (!service.system() && !service.characteristics().isEmpty()) {
                result.add(recommend(accessory, service));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Recommends a tile for one service. The service must belong to the supplied accessory.
     * Empty services still produce a harmless AUTO preset without source or action paths.
     */
    public SprutPopupPreset recommend(SprutCatalog.Accessory accessory,
                                      SprutCatalog.Service service) {
        Objects.requireNonNull(accessory, "accessory");
        Objects.requireNonNull(service, "service");
        if (accessory.id() != service.accessoryId()) {
            throw new IllegalArgumentException("Service does not belong to accessory "
                    + accessory.id());
        }

        Kind kind = classify(service);
        SprutCatalog.Characteristic primary = primaryCharacteristic(kind, service);
        SprutCatalog.Characteristic action = actionCharacteristic(kind, service, primary);
        if (primary == null && action != null && action.readable()) primary = action;

        SprutPopupPreset.ActionOperation operation = action == null ? null
                : isBoolean(action)
                ? SprutPopupPreset.ActionOperation.TOGGLE
                : SprutPopupPreset.ActionOperation.SET;

        return new SprutPopupPreset(
                allowedIcon(iconFor(kind)),
                titleFor(accessory, service),
                presentationFor(kind, primary),
                primary == null ? null : primary.path(),
                action == null ? null : action.path(),
                operation,
                defaultActionPayload(kind, action, operation),
                statusRulesFor(kind, primary),
                1,
                1);
    }

    private static Kind classify(SprutCatalog.Service service) {
        String typeKey = normalized(service.type());
        Kind explicit = explicitKind(typeKey);
        if (explicit != null) return explicit;

        // Names are only a fallback for vendor/opaque service types. A standard Switch named
        // "Garage" must remain a switch rather than becoming a garage-door controller.
        String serviceKey = normalized(service.type() + " " + service.name());
        StringBuilder characteristicKey = new StringBuilder();
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            characteristicKey.append(normalized(characteristic.type())).append(' ')
                    .append(normalized(characteristic.name())).append(' ');
        }
        String all = serviceKey + " " + characteristicKey;

        if (has(serviceKey, "garagedoor", "garage", "gate")) return Kind.GARAGE;
        if (has(serviceKey, "contactsensor")) return Kind.CONTACT;
        if (has(serviceKey, "windowcovering", "cover", "blind", "shutter", "shade",
                "curtain", "awning")) return Kind.COVER;
        if (has(serviceKey, "door", "window")
                && has(all, "currentposition", "targetposition", "currentdoorstate")) {
            return Kind.COVER;
        }
        if (has(serviceKey, "lockmechanism", "lock")) return Kind.LOCK;
        if (has(serviceKey, "thermostat", "heatercooler", "heater", "cooler", "climate")) {
            return Kind.THERMOSTAT;
        }
        // Prefer an explicit service family over optional characteristics embedded in it. For
        // example, an air-quality service may also expose CurrentTemperature.
        if (has(serviceKey, "humiditysensor", "humidity")) return Kind.HUMIDITY;
        if (has(serviceKey, "motionsensor", "motion")) return Kind.MOTION;
        if (has(serviceKey, "occupancysensor", "occupancy")) return Kind.OCCUPANCY;
        if (has(serviceKey, "leaksensor", "waterleak", "leak")) return Kind.LEAK;
        if (has(serviceKey, "smokesensor", "smokealarm", "smoke")) return Kind.SMOKE;
        if (has(serviceKey, "carbonmonoxide", "cosensor")) return Kind.CARBON_MONOXIDE;
        if (has(serviceKey, "fan", "airpurifier")) return Kind.FAN;
        if (has(serviceKey, "valve", "faucet", "irrigation", "sprinkler")) return Kind.VALVE;
        if (has(serviceKey, "securitysystem", "alarm", "security")) return Kind.SECURITY;
        if (has(serviceKey, "airqualitysensor", "airquality")) return Kind.AIR_QUALITY;
        if (has(serviceKey, "batteryservice", "battery")) return Kind.BATTERY;
        if (has(serviceKey, "temperaturesensor", "temperature")) return Kind.TEMPERATURE;
        if (has(serviceKey, "lightbulb", "lamp")
                || (has(serviceKey, "light") && has(all, "on", "active", "brightness"))) {
            return Kind.LIGHT;
        }
        if (has(serviceKey, "outlet", "socket", "plug")) return Kind.OUTLET;
        if (has(serviceKey, "switch", "relay")) return Kind.SWITCH;

        // Fall back to the characteristic family for vendor-specific C_* service types.
        if (has(all, "currentdoorstate", "текущийрежимдвери", "текущеесостояниедвери")
                && has(all, "targetdoorstate", "целевойрежимдвери",
                "целевоесостояниедвери")) return Kind.GARAGE;
        if (has(all, "currentposition", "текущаяпозиция", "текущееположение")
                && has(all, "targetposition", "целеваяпозиция", "целевоеположение")) {
            return Kind.COVER;
        }
        if (has(all, "contactsensorstate", "contactsensor")) return Kind.CONTACT;
        if (has(all, "temperaturesensor", "currenttemperature", "temperature")) {
            return Kind.TEMPERATURE;
        }
        if (has(all, "humiditysensor", "currentrelativehumidity", "humidity")) {
            return Kind.HUMIDITY;
        }
        if (has(all, "motionsensor", "motiondetected")) return Kind.MOTION;
        if (has(all, "occupancysensor", "occupancydetected")) return Kind.OCCUPANCY;
        if (has(all, "leaksensor", "leakdetected", "waterleak")) return Kind.LEAK;
        if (has(all, "smokesensor", "smokedetected", "smokealarm")) return Kind.SMOKE;
        if (has(all, "carbonmonoxide", "cosensor")) return Kind.CARBON_MONOXIDE;
        if (has(all, "airqualitysensor", "airquality")) return Kind.AIR_QUALITY;
        if (has(all, "batteryservice", "batterylevel", "statuslowbattery", "battery")) {
            return Kind.BATTERY;
        }
        return Kind.GENERIC;
    }

    private static Kind explicitKind(String type) {
        if (type.isEmpty()) return null;
        if (has(type, "garagedoor", "garage", "gate")) return Kind.GARAGE;
        if (has(type, "contactsensor")) return Kind.CONTACT;
        if (has(type, "windowcovering", "cover", "blind", "shutter", "shade", "curtain",
                "awning") || "door".equals(type) || "window".equals(type)) return Kind.COVER;
        if (has(type, "lockmechanism", "lock")) return Kind.LOCK;
        if (has(type, "thermostat", "heatercooler", "heater", "cooler", "climate")) {
            return Kind.THERMOSTAT;
        }
        if (has(type, "humiditysensor")) return Kind.HUMIDITY;
        if (has(type, "motionsensor")) return Kind.MOTION;
        if (has(type, "occupancysensor")) return Kind.OCCUPANCY;
        if (has(type, "leaksensor")) return Kind.LEAK;
        if (has(type, "smokesensor", "smokealarm")) return Kind.SMOKE;
        if (has(type, "carbonmonoxide", "cosensor")) return Kind.CARBON_MONOXIDE;
        if (has(type, "fan", "airpurifier")) return Kind.FAN;
        if (has(type, "valve", "faucet", "irrigation", "sprinkler")) return Kind.VALVE;
        if (has(type, "securitysystem", "alarm")) return Kind.SECURITY;
        if (has(type, "airqualitysensor")) return Kind.AIR_QUALITY;
        if (has(type, "batteryservice")) return Kind.BATTERY;
        if (has(type, "temperaturesensor")) return Kind.TEMPERATURE;
        if (has(type, "lightbulb")) return Kind.LIGHT;
        if (has(type, "outlet")) return Kind.OUTLET;
        if ("switch".equals(type) || has(type, "statelessprogrammableswitch")) {
            return Kind.SWITCH;
        }
        return null;
    }

    private static SprutCatalog.Characteristic primaryCharacteristic(
            Kind kind, SprutCatalog.Service service) {
        switch (kind) {
            case LIGHT:
            case SWITCH:
                return readable(service, "on", "active", "brightness", "statusactive");
            case OUTLET:
                return readable(service, "on", "outletinuse", "active", "statusactive");
            case GARAGE:
                return readable(service, "currentdoorstate", "currentposition", "positionstate",
                        "текущийрежимдвери", "текущеесостояниедвери", "текущаяпозиция",
                        "targetdoorstate", "targetposition", "obstructiondetected");
            case COVER:
                return readable(service, "currentposition", "positionstate", "currentdoorstate",
                        "текущаяпозиция", "текущееположение", "текущийрежимдвери",
                        "targetposition", "targetdoorstate", "obstructiondetected");
            case LOCK:
                return readable(service, "lockcurrentstate", "currentlockstate", "locktargetstate",
                        "targetlockstate");
            case THERMOSTAT:
                return readable(service, "currenttemperature", "currentheatingcoolingstate",
                        "currentheatercoolerstate", "active", "on");
            case TEMPERATURE:
                return readable(service, "currenttemperature", "temperature", "targettemperature");
            case HUMIDITY:
                return readable(service, "currentrelativehumidity", "relativehumidity", "humidity");
            case MOTION:
                return readable(service, "motiondetected", "statusactive", "active");
            case OCCUPANCY:
                return readable(service, "occupancydetected", "statusactive", "active");
            case CONTACT:
                return readable(service, "contactsensorstate", "contactstate", "statusactive");
            case LEAK:
                return readable(service, "leakdetected", "statusactive");
            case SMOKE:
                return readable(service, "smokedetected", "statusactive");
            case CARBON_MONOXIDE:
                return readable(service, "carbonmonoxidedetected", "carbonmonoxidelevel",
                        "statusactive");
            case FAN:
                return readable(service, "active", "on", "currentfanstate", "rotationspeed");
            case VALVE:
                return readable(service, "active", "inuse", "programmode", "remainingduration");
            case SECURITY:
                return readable(service, "securitysystemcurrentstate", "currentsecuritysystemstate",
                        "securitysystemtargetstate");
            case AIR_QUALITY:
                return readable(service, "airquality", "statusactive");
            case BATTERY:
                return readable(service, "batterylevel", "statuslowbattery", "chargingstate");
            case GENERIC:
            default:
                return readable(service, "currentstate", "state", "status", "currentvalue",
                        "value", "on", "active");
        }
    }

    private static SprutCatalog.Characteristic actionCharacteristic(
            Kind kind, SprutCatalog.Service service, SprutCatalog.Characteristic primary) {
        SprutCatalog.Characteristic preferred;
        switch (kind) {
            case LIGHT:
            case SWITCH:
            case OUTLET:
                preferred = writable(service, "on", "active", "brightness");
                break;
            case GARAGE:
                preferred = writable(service, "targetdoorstate", "targetposition",
                        "целевойрежимдвери", "целевоесостояниедвери", "целеваяпозиция",
                        "целевоеположение", "holdposition");
                break;
            case COVER:
                preferred = writable(service, "targetposition", "targetdoorstate",
                        "целеваяпозиция", "целевоеположение", "целевойрежимдвери",
                        "целевоесостояниедвери", "holdposition");
                break;
            case LOCK:
                preferred = writable(service, "locktargetstate", "targetlockstate");
                break;
            case THERMOSTAT:
                preferred = writable(service, "active", "on", "targetheatingcoolingstate",
                        "targetheatercoolerstate", "targettemperature");
                break;
            case FAN:
                preferred = writable(service, "active", "on", "targetfanstate", "rotationspeed");
                break;
            case VALVE:
                preferred = writable(service, "active", "on", "setduration");
                break;
            case SECURITY:
                preferred = writable(service, "securitysystemtargetstate",
                        "targetsecuritysystemstate");
                break;
            case TEMPERATURE:
            case HUMIDITY:
            case MOTION:
            case OCCUPANCY:
            case CONTACT:
            case LEAK:
            case SMOKE:
            case CARBON_MONOXIDE:
            case AIR_QUALITY:
            case BATTERY:
                preferred = null;
                break;
            case GENERIC:
            default:
                preferred = writable(service, "targetstate", "targetvalue", "setpoint", "on",
                        "active", "enabled", "value");
                break;
        }
        if (preferred != null) return preferred;
        // Only families whose displayed state is also conventionally their control may fall
        // back to the primary value. Covers, locks and climate services have separate current
        // and target characteristics; treating an arbitrary writable primary as their command
        // would recreate the CurrentDoorState bug (or worse, select a vendor setting).
        if (primary != null && primary.writable()
                && (kind == Kind.LIGHT || kind == Kind.SWITCH || kind == Kind.OUTLET
                || kind == Kind.FAN || kind == Kind.VALVE || kind == Kind.GENERIC)) {
            return primary;
        }
        return null;
    }

    private static SprutPopupPreset.Presentation presentationFor(
            Kind kind, SprutCatalog.Characteristic primary) {
        switch (kind) {
            case GARAGE:
            case COVER:
                return SprutPopupPreset.Presentation.COVER;
            case LIGHT:
            case SWITCH:
            case OUTLET:
                return isBoolean(primary) ? SprutPopupPreset.Presentation.BOOLEAN
                        : SprutPopupPreset.Presentation.RAW;
            case MOTION:
            case OCCUPANCY:
            case CONTACT:
            case LEAK:
            case SMOKE:
                return SprutPopupPreset.Presentation.BOOLEAN;
            case CARBON_MONOXIDE:
            case FAN:
            case VALVE:
                return isBoolean(primary) ? SprutPopupPreset.Presentation.BOOLEAN
                        : SprutPopupPreset.Presentation.RAW;
            case THERMOSTAT:
                return primary != null && has(normalized(primary.type() + primary.name()),
                        "temperature", "temp")
                        ? SprutPopupPreset.Presentation.TEMPERATURE
                        : SprutPopupPreset.Presentation.RAW;
            case TEMPERATURE:
                return SprutPopupPreset.Presentation.TEMPERATURE;
            case LOCK:
            case HUMIDITY:
            case SECURITY:
            case AIR_QUALITY:
            case BATTERY:
                return SprutPopupPreset.Presentation.RAW;
            case GENERIC:
            default:
                return inferPresentation(primary);
        }
    }

    private static SprutPopupPreset.Presentation inferPresentation(
            SprutCatalog.Characteristic characteristic) {
        if (characteristic == null) return SprutPopupPreset.Presentation.AUTO;
        String key = normalized(characteristic.type() + " " + characteristic.name());
        String unit = normalized(characteristic.unit());
        if (has(key, "currentposition", "targetposition", "doorstate")) {
            return SprutPopupPreset.Presentation.COVER;
        }
        if (has(key, "temperature", "temp") || has(unit, "celsius", "fahrenheit")) {
            return SprutPopupPreset.Presentation.TEMPERATURE;
        }
        if (isBoolean(characteristic)) return SprutPopupPreset.Presentation.BOOLEAN;
        return SprutPopupPreset.Presentation.RAW;
    }

    private static Object defaultActionPayload(Kind kind, SprutCatalog.Characteristic action,
                                               SprutPopupPreset.ActionOperation operation) {
        if (action == null || operation == null
                || operation == SprutPopupPreset.ActionOperation.TOGGLE) return null;
        String type = normalized(action.type() + " " + action.name());
        switch (kind) {
            case GARAGE:
            case COVER:
                if (has(type, "targetposition", "целеваяпозиция", "целевоеположение")) {
                    return action.maxValue() == null ? 100 : action.maxValue();
                }
                Object open = validValue(action, ValueIntent.OPEN,
                        "open", "opened", "откры");
                return open != null ? open : 0;
            case LOCK:
                Object secured = validValue(action, ValueIntent.SECURED,
                        "secured", "locked", "закры");
                return secured != null ? secured : 1;
            case GENERIC:
                return action.validValues().size() == 1
                        ? action.validValues().get(0).value() : null;
            case THERMOSTAT:
            case SECURITY:
            default:
                // Modes are safety- or comfort-sensitive; the editor must ask for a target.
                return null;
        }
    }

    private static List<SprutPopupPreset.StatusRule> statusRulesFor(
            Kind kind, SprutCatalog.Characteristic primary) {
        if (primary == null) return Collections.emptyList();
        String type = normalized(primary.type() + " " + primary.name());
        List<SprutPopupPreset.StatusRule> rules = new ArrayList<>();
        switch (kind) {
            case GARAGE:
            case COVER:
                if (has(type, "doorstate", "режимдвери", "состояниедвери")) {
                    exact(rules, 0, "Открыто", GREEN);
                    exact(rules, 1, "Закрыто", WHITE);
                    exact(rules, 2, "Открывается", ORANGE);
                    exact(rules, 3, "Закрывается", RED);
                    exact(rules, 4, "Остановлено", YELLOW);
                } else if (has(type, "position", "позиция", "положение")) {
                    exact(rules, 0, "Закрыто", WHITE);
                    exact(rules, 100, "Открыто", GREEN);
                    rules.add(SprutPopupPreset.StatusRule.range(0d, 100d,
                            "Движение", ORANGE));
                }
                break;
            case LIGHT:
            case SWITCH:
            case OUTLET:
            case FAN:
            case VALVE:
                if (isBoolean(primary)) booleanRules(rules, "Выкл.", "Вкл.", GREEN);
                break;
            case LOCK:
                exact(rules, 0, "Открыт", RED);
                exact(rules, 1, "Закрыт", GREEN);
                exact(rules, 2, "Заклинило", RED);
                exact(rules, 3, "Неизвестно", GRAY);
                break;
            case THERMOSTAT:
                if (has(type, "heatingcoolingstate", "heatercoolerstate")) {
                    exact(rules, 0, "Выкл.", WHITE);
                    exact(rules, 1, "Нагрев", ORANGE);
                    exact(rules, 2, "Охлаждение", BLUE);
                    exact(rules, 3, "Авто", GREEN);
                }
                break;
            case HUMIDITY:
                rules.add(SprutPopupPreset.StatusRule.range(30d, 60d,
                        "Норма", GREEN));
                rules.add(SprutPopupPreset.StatusRule.range(null, 30d,
                        "Сухо", ORANGE));
                rules.add(SprutPopupPreset.StatusRule.range(60d, null,
                        "Влажно", BLUE));
                break;
            case MOTION:
                booleanRules(rules, "Нет движения", "Движение", ORANGE);
                break;
            case OCCUPANCY:
                booleanRules(rules, "Никого", "Присутствие", GREEN);
                break;
            case CONTACT:
                exact(rules, 0, "Закрыто", WHITE);
                exact(rules, 1, "Открыто", ORANGE);
                break;
            case LEAK:
                exact(rules, 0, "Сухо", WHITE);
                exact(rules, 1, "Протечка", RED);
                break;
            case SMOKE:
                exact(rules, 0, "Норма", WHITE);
                exact(rules, 1, "Дым", RED);
                break;
            case CARBON_MONOXIDE:
                if (has(type, "detected")) {
                    exact(rules, 0, "Норма", WHITE);
                    exact(rules, 1, "Угарный газ", RED);
                }
                break;
            case SECURITY:
                exact(rules, 0, "Дома", GREEN);
                exact(rules, 1, "Вне дома", ORANGE);
                exact(rules, 2, "Ночь", BLUE);
                exact(rules, 3, "Снято", WHITE);
                exact(rules, 4, "Тревога", RED);
                break;
            case AIR_QUALITY:
                exact(rules, 0, "Неизвестно", GRAY);
                exact(rules, 1, "Отлично", GREEN);
                exact(rules, 2, "Хорошо", GREEN);
                exact(rules, 3, "Средне", YELLOW);
                exact(rules, 4, "Плохо", ORANGE);
                exact(rules, 5, "Очень плохо", RED);
                break;
            case BATTERY:
                if (has(type, "statuslowbattery")) {
                    exact(rules, 0, "Норма", GREEN);
                    exact(rules, 1, "Низкий заряд", RED);
                } else {
                    rules.add(SprutPopupPreset.StatusRule.range(null, 15d,
                            "Критический заряд", RED));
                    rules.add(SprutPopupPreset.StatusRule.range(15d, 30d,
                            "Низкий заряд", ORANGE));
                    rules.add(SprutPopupPreset.StatusRule.range(30d, null,
                            "Заряд", GREEN));
                }
                break;
            case TEMPERATURE:
                break;
            case GENERIC:
            default:
                if (isBoolean(primary)) booleanRules(rules, "Выкл.", "Вкл.", GREEN);
                break;
        }
        if (rules.isEmpty()) rules.addAll(validValueRules(primary));
        return rules;
    }

    private static List<SprutPopupPreset.StatusRule> validValueRules(
            SprutCatalog.Characteristic characteristic) {
        if (characteristic.validValues().isEmpty()) return Collections.emptyList();
        List<SprutPopupPreset.StatusRule> result = new ArrayList<>();
        for (SprutCatalog.ValidValue valid : characteristic.validValues()) {
            String label = firstNonBlank(valid.name(), valid.key(), String.valueOf(valid.value()));
            result.add(SprutPopupPreset.StatusRule.exact(valid.value(), label,
                    semanticColor(label)));
        }
        return result;
    }

    private static SprutCatalog.Characteristic readable(SprutCatalog.Service service,
                                                         String... preferredTypes) {
        return best(service.characteristics(), false, preferredTypes);
    }

    private static SprutCatalog.Characteristic writable(SprutCatalog.Service service,
                                                         String... preferredTypes) {
        return best(service.characteristics(), true, preferredTypes);
    }

    private static SprutCatalog.Characteristic best(
            List<SprutCatalog.Characteristic> characteristics, boolean writable,
            String... preferredTypes) {
        List<ScoredCharacteristic> candidates = new ArrayList<>();
        for (int index = 0; index < characteristics.size(); index++) {
            SprutCatalog.Characteristic characteristic = characteristics.get(index);
            if (writable ? !characteristic.writable() : !characteristic.readable()) continue;
            int score = preferenceScore(characteristic, preferredTypes);
            // A command target must be both writable and semantically related to the requested
            // operation.  Previously any writable sibling won when none of the preferred target
            // types existed.  A read-only CurrentDoorState could therefore be paired with an
            // unrelated writable vendor setting and the resulting gate tile looked actionable
            // while writing the wrong characteristic.  Readable display selection deliberately
            // keeps its generic fallback; command selection must fail closed.
            if (writable && preferredTypes.length > 0 && score == 0) continue;
            // A hidden/internal diagnostic must not beat a visible value merely because its
            // technical type has a preferred name such as "State".
            if (characteristic.hidden()) score -= 2000;
            else score += 50;
            if (!characteristic.visible()) score -= 1000;
            else score += 25;
            if (!writable && characteristic.events()) score += 5;
            if (!writable && characteristic.currentValue() != null) score += 3;
            candidates.add(new ScoredCharacteristic(characteristic, score, index));
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingInt(ScoredCharacteristic::score).reversed()
                .thenComparingInt(ScoredCharacteristic::index));
        return candidates.get(0).characteristic();
    }

    private static int preferenceScore(SprutCatalog.Characteristic characteristic,
                                       String... preferredTypes) {
        String type = normalized(characteristic.type());
        String name = normalized(characteristic.name());
        int score = 0;
        for (int index = 0; index < preferredTypes.length; index++) {
            String preferred = normalized(preferredTypes[index]);
            int priority = Math.max(0, 100 - index);
            if (type.equals(preferred)) score = Math.max(score, 1000 + priority);
            else if (type.contains(preferred)) score = Math.max(score, 800 + priority);
            else if (name.equals(preferred)) score = Math.max(score, 600 + priority);
            else if (name.contains(preferred)) score = Math.max(score, 400 + priority);
        }
        return score;
    }

    private static boolean isBoolean(SprutCatalog.Characteristic characteristic) {
        if (characteristic == null) return false;
        if (characteristic.valueType() == SprutCatalog.ValueType.BOOLEAN
                || characteristic.currentValue() instanceof Boolean) return true;
        String format = normalized(characteristic.format());
        if (has(format, "bool", "boolean")) return true;
        String type = normalized(characteristic.type() + " " + characteristic.name());
        if (has(type, "detected")) return true;
        return has(type, "on", "active", "enabled")
                && characteristic.minValue() != null && characteristic.maxValue() != null
                && characteristic.minValue().doubleValue() == 0d
                && characteristic.maxValue().doubleValue() == 1d;
    }

    private static Object validValue(SprutCatalog.Characteristic characteristic,
                                     ValueIntent intent, String... labels) {
        for (SprutCatalog.ValidValue valid : characteristic.validValues()) {
            String text = normalized(valid.key() + " " + valid.name());
            if (intent == ValueIntent.OPEN && has(text, "closed", "secured", "locked",
                    "закрыто", "закрыт")) continue;
            if (intent == ValueIntent.SECURED && has(text, "unsecured", "unlocked",
                    "open", "откры")) continue;
            if (has(text, labels)) return valid.value();
        }
        return null;
    }

    private static String iconFor(Kind kind) {
        switch (kind) {
            case LIGHT: return "light";
            case GARAGE: return "garage";
            case COVER: return "gate";
            case LOCK: return "lock";
            case THERMOSTAT:
                return "thermostat";
            case TEMPERATURE: return "temperature";
            case HUMIDITY: return "humidity";
            case LEAK:
            case VALVE: return "water";
            case MOTION:
            case OCCUPANCY: return "motion";
            case CONTACT: return "door";
            case OUTLET: return "plug";
            case SMOKE:
            case CARBON_MONOXIDE: return "smoke";
            case FAN: return "fan";
            case BATTERY: return "battery";
            case SECURITY: return "alarm";
            case SWITCH:
            case AIR_QUALITY:
            case GENERIC:
            default: return "power";
        }
    }

    private static String allowedIcon(String requested) {
        return PopupIconCatalog.IDS.contains(requested) ? requested : "power";
    }

    private static String titleFor(SprutCatalog.Accessory accessory,
                                   SprutCatalog.Service service) {
        String serviceName = service.name() == null ? "" : service.name().trim();
        String type = service.type() == null ? "" : service.type().trim();
        if (!serviceName.isEmpty() && !normalized(serviceName).equals(normalized(type))) {
            return serviceName;
        }
        String accessoryName = accessory.name() == null ? "" : accessory.name().trim();
        if (!accessoryName.isEmpty()) return accessoryName;
        if (!serviceName.isEmpty()) return serviceName;
        if (!type.isEmpty()) return humanize(type);
        return "Sprut.hub";
    }

    private static String humanize(String value) {
        String text = value.replace('_', ' ').replace('-', ' ').trim();
        if (text.isEmpty()) return "Sprut.hub";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (index > 0 && Character.isUpperCase(current)
                    && Character.isLowerCase(text.charAt(index - 1))) result.append(' ');
            result.append(current);
        }
        return result.toString().trim();
    }

    private static void booleanRules(List<SprutPopupPreset.StatusRule> result,
                                     String falseLabel, String trueLabel, String trueColor) {
        result.add(SprutPopupPreset.StatusRule.exact(false, falseLabel, WHITE));
        result.add(SprutPopupPreset.StatusRule.exact(true, trueLabel, trueColor));
    }

    private static void exact(List<SprutPopupPreset.StatusRule> result, Object value,
                              String label, String color) {
        result.add(SprutPopupPreset.StatusRule.exact(value, label, color));
    }

    private static String semanticColor(String rawLabel) {
        String label = normalized(rawLabel);
        if (has(label, "error", "alarm", "smoke", "leak", "critical", "poor", "danger",
                "ошиб", "тревог", "дым", "протеч", "авари", "критич")) return RED;
        if (has(label, "opening", "closing", "warning", "low", "движ", "низк")) {
            return ORANGE;
        }
        if (has(label, "open", "active", "on", "good", "normal", "откры", "вкл",
                "норма")) return GREEN;
        if (has(label, "cool", "blue", "охлаж")) return BLUE;
        return WHITE;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String normalized(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char value = lower.charAt(index);
            if (Character.isLetterOrDigit(value)) result.append(value);
        }
        return result.toString();
    }

    private static boolean has(String source, String... needles) {
        if (source == null || source.isEmpty()) return false;
        for (String needle : needles) {
            String normalizedNeedle = normalized(needle);
            if (!normalizedNeedle.isEmpty() && source.contains(normalizedNeedle)) return true;
        }
        return false;
    }

    private enum Kind {
        LIGHT,
        SWITCH,
        OUTLET,
        GARAGE,
        COVER,
        LOCK,
        THERMOSTAT,
        TEMPERATURE,
        HUMIDITY,
        MOTION,
        OCCUPANCY,
        CONTACT,
        LEAK,
        SMOKE,
        CARBON_MONOXIDE,
        FAN,
        VALVE,
        SECURITY,
        AIR_QUALITY,
        BATTERY,
        GENERIC
    }

    private enum ValueIntent {
        OPEN,
        SECURED
    }

    private static final class ScoredCharacteristic {
        private final SprutCatalog.Characteristic characteristic;
        private final int score;
        private final int index;

        ScoredCharacteristic(SprutCatalog.Characteristic characteristic, int score, int index) {
            this.characteristic = characteristic;
            this.score = score;
            this.index = index;
        }

        SprutCatalog.Characteristic characteristic() { return characteristic; }

        int score() { return score; }

        int index() { return index; }
    }
}
