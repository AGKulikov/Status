/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** JSON protocol boundary for Sprut.hub's WebSocket JSON-RPC API. */
public final class SprutProtocolAdapter {
    public static final String EVENT_UPDATE = "EVENT_UPDATE";
    public static final String EXPAND_COMMA = "services,characteristics";
    public static final String EXPAND_PLUS = "services+characteristics";

    private SprutProtocolAdapter() {}

    public static SprutCatalog parseCatalog(JSONObject roomSnapshot, JSONObject accessorySnapshot) {
        return new SprutCatalog(parseRoomListSnapshot(roomSnapshot),
                parseAccessoryListSnapshot(accessorySnapshot));
    }

    public static SprutCatalog parseCatalog(String roomSnapshot, String accessorySnapshot)
            throws JSONException {
        return parseCatalog(new JSONObject(roomSnapshot), new JSONObject(accessorySnapshot));
    }

    /** Accepts a complete JSON-RPC response, its {@code result}, or the {@code room.list} body. */
    public static List<SprutCatalog.Room> parseRoomListSnapshot(JSONObject snapshot) {
        JSONObject body = findCommandBody(Objects.requireNonNull(snapshot, "snapshot"),
                "room", "list");
        JSONArray array = body == null ? findArray(snapshot, "rooms") : body.optJSONArray("rooms");
        if (array == null) {
            throw new IllegalArgumentException("Sprut.hub room.list response has no rooms array");
        }

        List<SprutCatalog.Room> rooms = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject room = array.optJSONObject(index);
            if (room == null) continue;
            rooms.add(new SprutCatalog.Room(
                    requireLong(room, "id"),
                    room.optString("name", ""),
                    room.optInt("order", index),
                    room.optString("type", ""),
                    optBoolean(room, "visible", true)));
        }
        return Collections.unmodifiableList(rooms);
    }

    /**
     * Accepts both snapshots requested with {@code services,characteristics} and with
     * {@code services+characteristics}; the response shape is identical.
     */
    public static List<SprutCatalog.Accessory> parseAccessoryListSnapshot(JSONObject snapshot) {
        JSONObject body = findCommandBody(Objects.requireNonNull(snapshot, "snapshot"),
                "accessory", "list");
        JSONArray array = body == null
                ? findArray(snapshot, "accessories") : body.optJSONArray("accessories");
        if (array == null) {
            throw new IllegalArgumentException(
                    "Sprut.hub accessory.list response has no accessories array");
        }

        List<SprutCatalog.Accessory> accessories = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject accessory = array.optJSONObject(index);
            if (accessory == null) continue;
            long accessoryId = requireLong(accessory, "id");
            List<SprutCatalog.Service> services = parseServices(
                    accessory.optJSONArray("services"), accessoryId);
            accessories.add(new SprutCatalog.Accessory(
                    accessoryId,
                    nullableLong(accessory, "roomId"),
                    accessory.optString("name", ""),
                    accessory.optString("manufacturer", ""),
                    accessory.optString("model", ""),
                    accessory.optString("serial", ""),
                    accessory.optString("firmware", ""),
                    optBoolean(accessory, "online", false),
                    optBoolean(accessory, "virtual", false),
                    services));
        }
        return Collections.unmodifiableList(accessories);
    }

    /** Parses every characteristic in one {@code EVENT_UPDATE} message without mutating a catalog. */
    public static List<EventUpdate> parseEventUpdate(JSONObject message) {
        Objects.requireNonNull(message, "message");
        JSONObject eventRoot = message.optJSONObject("event");
        if (eventRoot == null) eventRoot = message;
        JSONObject characteristic = eventRoot.optJSONObject("characteristic");
        if (characteristic == null) return Collections.emptyList();
        if (!EVENT_UPDATE.equals(characteristic.optString("event", EVENT_UPDATE))) {
            return Collections.emptyList();
        }

        JSONArray updates = characteristic.optJSONArray("characteristics");
        if (updates == null) {
            JSONObject single = characteristic.optJSONObject("characteristic");
            if (single == null && characteristic.has("aId")) single = characteristic;
            if (single == null) return Collections.emptyList();
            updates = new JSONArray().put(single);
        }

        List<EventUpdate> result = new ArrayList<>(updates.length());
        for (int index = 0; index < updates.length(); index++) {
            JSONObject update = updates.optJSONObject(index);
            if (update == null) continue;
            JSONObject control = update.optJSONObject("control");
            JSONObject values = control == null ? update : control;
            DecodedValue decoded = decodeValue(values.opt("value"));
            result.add(new EventUpdate(
                    new SprutPath(requireLong(update, "aId"), requireLong(update, "sId"),
                            requireLong(update, "cId")),
                    decoded.value,
                    decoded.type));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<EventUpdate> parseEventUpdates(JSONObject message) {
        return parseEventUpdate(message);
    }

    /** Applies known paths and returns their count. Unknown paths signal that a fresh list is needed. */
    public static int applyEventUpdate(SprutCatalog catalog, JSONObject message) {
        Objects.requireNonNull(catalog, "catalog");
        int applied = 0;
        for (EventUpdate update : parseEventUpdate(message)) {
            SprutCatalog.Characteristic characteristic = catalog.find(update.path());
            if (characteristic != null) {
                characteristic.updateCurrentValue(update.value(), update.valueType());
                applied++;
            }
        }
        return applied;
    }

    /** First step of the current challenge/answer login flow. */
    public static JSONObject buildAuthParams() {
        return object("account", object("auth", object("params", new JSONArray())));
    }

    public static JSONObject buildAuthAnswerParams(String answer) {
        return object("account", object("answer", object("data",
                Objects.requireNonNull(answer, "answer"))));
    }

    /** Compatibility request used by Sprut.hub revisions predating the email challenge flow. */
    public static JSONObject buildLegacyLoginParams(String email) {
        return object("account", object("login", object("login",
                Objects.requireNonNull(email, "email"))));
    }

    public static JSONObject buildRoomListParams() {
        return object("room", object("list", new JSONObject()));
    }

    public static JSONObject buildAccessoryListParams() {
        return buildAccessoryListParams(EXPAND_PLUS);
    }

    public static JSONObject buildAccessoryListParams(String expand) {
        String normalized = expand == null || expand.trim().isEmpty()
                ? EXPAND_PLUS : expand.trim().toLowerCase(Locale.ROOT);
        if (!EXPAND_PLUS.equals(normalized) && !EXPAND_COMMA.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported accessory expansion: " + expand);
        }
        return object("accessory", object("list", object("expand", normalized)));
    }

    public static JSONObject buildCharacteristicUpdateParams(
            SprutCatalog.Characteristic characteristic, Object newValue) {
        Objects.requireNonNull(characteristic, "characteristic");
        return buildCharacteristicUpdateParams(
                characteristic.path(), newValue, characteristic.valueType());
    }

    /**
     * Compatibility payload used by hubs predating the nested {@code control.value} update
     * shape. Keeping both encoders at the protocol boundary makes typed telemetry writes and
     * interactive actions use exactly the same value coercion rules.
     */
    public static JSONObject buildLegacyCharacteristicUpdateParams(
            SprutCatalog.Characteristic characteristic, Object newValue) {
        Objects.requireNonNull(characteristic, "characteristic");
        return legacyUpdate(buildCharacteristicUpdateParams(characteristic, newValue));
    }

    /** Legacy envelope while preserving the already validated Java value's exact wire type. */
    public static JSONObject buildLegacyCharacteristicUpdateParams(
            SprutPath path, Object newValue) {
        return legacyUpdate(buildCharacteristicUpdateParams(path, newValue));
    }

    private static JSONObject legacyUpdate(JSONObject params) {
        JSONObject characteristicNode = params.optJSONObject("characteristic");
        JSONObject update = characteristicNode == null ? null
                : characteristicNode.optJSONObject("update");
        JSONObject control = update == null ? null : update.optJSONObject("control");
        if (update == null || control == null || !control.has("value")) {
            throw new IllegalArgumentException("Cannot encode legacy characteristic update");
        }
        try {
            update.put("value", control.get("value"));
            update.remove("control");
            return params;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Cannot encode legacy characteristic update",
                    error);
        }
    }

    public static JSONObject buildCharacteristicUpdateParams(SprutPath path, Object newValue) {
        return buildCharacteristicUpdateParams(path, newValue, inferType(newValue));
    }

    public static JSONObject buildCharacteristicUpdateParams(
            SprutPath path, Object newValue, SprutCatalog.ValueType preferredType) {
        Objects.requireNonNull(path, "path");
        DecodedValue encoded = coerceForType(newValue, preferredType);
        if (encoded.value == null) {
            throw new IllegalArgumentException("Characteristic value must not be null");
        }
        SprutCatalog.ValueType type = encoded.type == SprutCatalog.ValueType.UNKNOWN
                ? inferType(encoded.value) : encoded.type;
        if (type == SprutCatalog.ValueType.UNKNOWN || type.jsonKey().isEmpty()) {
            throw new IllegalArgumentException("Unsupported characteristic value: " + newValue);
        }
        JSONObject value = object(type.jsonKey(), encoded.value);
        JSONObject update = object(
                "aId", path.accessoryId(),
                "sId", path.serviceId(),
                "cId", path.characteristicId(),
                "control", object("value", value));
        return object("characteristic", object("update", update));
    }

    /** Converts a protobuf-style value wrapper into Boolean, Number, String, or {@code null}. */
    public static Object typedValue(Object raw) {
        return decodeValue(raw).value;
    }

    public static SprutCatalog.ValueType typedValueType(Object raw) {
        return decodeValue(raw).type;
    }

    private static List<SprutCatalog.Service> parseServices(JSONArray array, long accessoryId) {
        if (array == null) return Collections.emptyList();
        List<SprutCatalog.Service> services = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject service = array.optJSONObject(index);
            if (service == null) continue;
            long serviceId = requireLong(service, "sId");
            String serviceType = service.optString("type", "");
            List<SprutCatalog.Characteristic> characteristics = parseCharacteristics(
                    service.optJSONArray("characteristics"), accessoryId, serviceId, serviceType);
            services.add(new SprutCatalog.Service(
                    accessoryId,
                    serviceId,
                    service.optString("name", serviceType),
                    serviceType,
                    service.optInt("order", index),
                    optBoolean(service, "visible", true),
                    optBoolean(service, "system", false),
                    characteristics));
        }
        return services;
    }

    private static List<SprutCatalog.Characteristic> parseCharacteristics(
            JSONArray array, long fallbackAccessoryId, long fallbackServiceId, String serviceType) {
        if (array == null) return Collections.emptyList();
        List<SprutCatalog.Characteristic> characteristics = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject characteristic = array.optJSONObject(index);
            if (characteristic == null) continue;
            JSONObject control = characteristic.optJSONObject("control");
            JSONObject metadata = control == null ? characteristic : control;
            Object rawValue = metadata.has("value") ? metadata.opt("value")
                    : characteristic.opt("value");
            DecodedValue decoded = decodeValue(rawValue);
            long accessoryId = optLong(characteristic, "aId", fallbackAccessoryId);
            long serviceId = optLong(characteristic, "sId", fallbackServiceId);
            long characteristicId = requireLong(characteristic, "cId");
            String type = firstNonBlank(metadata.optString("type", ""),
                    characteristic.optString("type", ""));
            String name = firstNonBlank(metadata.optString("name", ""),
                    characteristic.optString("name", ""), type);
            JSONArray validValues = metadata.optJSONArray("validValues");
            if (validValues == null && metadata != characteristic) {
                // Some Sprut/plugin revisions keep constraints on the characteristic while the
                // live value and permissions are nested in control.
                validValues = characteristic.optJSONArray("validValues");
            }
            characteristics.add(new SprutCatalog.Characteristic(
                    new SprutPath(accessoryId, serviceId, characteristicId),
                    serviceType,
                    name,
                    type,
                    metadata.optString("format", characteristic.optString("format", "")),
                    metadata.optString("unit", characteristic.optString("unit", "")),
                    optBoolean(metadata, "read", optBoolean(characteristic, "read", false)),
                    optBoolean(metadata, "write", optBoolean(characteristic, "write", false)),
                    optBoolean(metadata, "events", optBoolean(characteristic, "events", false)),
                    optBoolean(metadata, "visible",
                            optBoolean(characteristic, "statusVisible", true)),
                    optBoolean(metadata, "hidden", optBoolean(characteristic, "hidden", false)),
                    number(metadata, characteristic, "minValue"),
                    number(metadata, characteristic, "maxValue"),
                    number(metadata, characteristic, "minStep"),
                    parseValidValues(validValues),
                    decoded.value,
                    decoded.type));
        }
        return characteristics;
    }

    private static List<SprutCatalog.ValidValue> parseValidValues(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<SprutCatalog.ValidValue> values = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) continue;
            values.add(new SprutCatalog.ValidValue(
                    typedValue(item.opt("value")),
                    item.optString("key", ""),
                    item.optString("name", "")));
        }
        return values;
    }

    private static JSONObject findCommandBody(JSONObject source, String category, String action) {
        for (JSONObject candidate : candidates(source)) {
            JSONObject categoryBody = candidate.optJSONObject(category);
            if (categoryBody == null) continue;
            JSONObject actionBody = categoryBody.optJSONObject(action);
            if (actionBody != null) return decodeEmbeddedData(actionBody);
        }
        if (source.has(category.equals("room") ? "rooms" : "accessories")) return source;
        return null;
    }

    private static JSONArray findArray(JSONObject source, String name) {
        for (JSONObject candidate : candidates(source)) {
            JSONArray direct = candidate.optJSONArray(name);
            if (direct != null) return direct;
            JSONObject data = candidate.optJSONObject("data");
            if (data != null && data.optJSONArray(name) != null) return data.optJSONArray(name);
        }
        return null;
    }

    private static List<JSONObject> candidates(JSONObject source) {
        List<JSONObject> candidates = new ArrayList<>(4);
        candidates.add(source);
        JSONObject result = source.optJSONObject("result");
        if (result != null) candidates.add(result);
        JSONObject data = source.optJSONObject("data");
        if (data != null) candidates.add(data);
        if (result != null && result.optJSONObject("data") != null) {
            candidates.add(result.optJSONObject("data"));
        }
        return candidates;
    }

    private static JSONObject decodeEmbeddedData(JSONObject body) {
        Object data = body.opt("data");
        if (data instanceof JSONObject) return (JSONObject) data;
        if (data instanceof String) {
            try {
                return new JSONObject((String) data);
            } catch (JSONException ignored) {
                return body;
            }
        }
        return body;
    }

    private static DecodedValue decodeValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return new DecodedValue(null, SprutCatalog.ValueType.UNKNOWN);
        }
        if (!(raw instanceof JSONObject)) {
            return new DecodedValue(raw, inferType(raw));
        }
        JSONObject wrapper = (JSONObject) raw;
        for (SprutCatalog.ValueType type : SprutCatalog.ValueType.values()) {
            if (type == SprutCatalog.ValueType.UNKNOWN || !wrapper.has(type.jsonKey())) continue;
            Object value = wrapper.opt(type.jsonKey());
            if (value == JSONObject.NULL) value = null;
            return coerceForType(value, type);
        }
        return new DecodedValue(null, SprutCatalog.ValueType.UNKNOWN);
    }

    private static DecodedValue coerceForType(Object raw, SprutCatalog.ValueType requested) {
        SprutCatalog.ValueType type = requested == null || requested == SprutCatalog.ValueType.UNKNOWN
                ? inferType(raw) : requested;
        if (raw == null || raw == JSONObject.NULL) return new DecodedValue(null, type);
        try {
            switch (type) {
                case BOOLEAN:
                    if (raw instanceof Boolean) return new DecodedValue(raw, type);
                    if (raw instanceof Number) {
                        return new DecodedValue(((Number) raw).doubleValue() != 0d, type);
                    }
                    String bool = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                    return new DecodedValue("true".equals(bool) || "1".equals(bool)
                            || "on".equals(bool), type);
                case INTEGER:
                    return new DecodedValue(raw instanceof Number
                            ? ((Number) raw).intValue() : Integer.parseInt(String.valueOf(raw)), type);
                case LONG:
                    return new DecodedValue(raw instanceof Number
                            ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw)), type);
                case FLOAT:
                    return new DecodedValue(raw instanceof Number
                            ? ((Number) raw).floatValue() : Float.parseFloat(String.valueOf(raw)), type);
                case DOUBLE:
                    return new DecodedValue(raw instanceof Number
                            ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw)), type);
                case STRING:
                    return new DecodedValue(String.valueOf(raw), type);
                case UNKNOWN:
                default:
                    return new DecodedValue(raw, inferType(raw));
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Value does not match " + type + ": " + raw, error);
        }
    }

    private static SprutCatalog.ValueType inferType(Object value) {
        if (value instanceof Boolean) return SprutCatalog.ValueType.BOOLEAN;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return SprutCatalog.ValueType.INTEGER;
        }
        if (value instanceof Long) return SprutCatalog.ValueType.LONG;
        if (value instanceof Float) return SprutCatalog.ValueType.FLOAT;
        if (value instanceof Number) return SprutCatalog.ValueType.DOUBLE;
        if (value instanceof CharSequence || value instanceof Character) {
            return SprutCatalog.ValueType.STRING;
        }
        return SprutCatalog.ValueType.UNKNOWN;
    }

    private static Number number(JSONObject preferred, JSONObject fallback, String name) {
        Object raw = preferred.has(name) ? preferred.opt(name) : fallback.opt(name);
        return raw instanceof Number ? (Number) raw : null;
    }

    private static long requireLong(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) {
            throw new IllegalArgumentException("Missing numeric field: " + name);
        }
        Object raw = object.opt(name);
        if (raw instanceof Number) return ((Number) raw).longValue();
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid numeric field " + name + ": " + raw, error);
        }
    }

    private static long optLong(JSONObject object, String name, long fallback) {
        if (!object.has(name) || object.isNull(name)) return fallback;
        return requireLong(object, name);
    }

    private static Long nullableLong(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) return null;
        return requireLong(object, name);
    }

    private static boolean optBoolean(JSONObject object, String name, boolean fallback) {
        if (object == null || !object.has(name) || object.isNull(name)) return fallback;
        Object raw = object.opt(name);
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index < pairs.length; index += 2) {
                result.put(String.valueOf(pairs[index]), pairs[index + 1]);
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("Cannot encode Sprut.hub JSON", error);
        }
        return result;
    }

    public static final class EventUpdate {
        private final SprutPath path;
        private final Object value;
        private final SprutCatalog.ValueType valueType;

        EventUpdate(SprutPath path, Object value, SprutCatalog.ValueType valueType) {
            this.path = path;
            this.value = value;
            this.valueType = valueType;
        }

        public SprutPath path() { return path; }

        public Object value() { return value; }

        public SprutCatalog.ValueType valueType() { return valueType; }
    }

    private static final class DecodedValue {
        final Object value;
        final SprutCatalog.ValueType type;

        DecodedValue(Object value, SprutCatalog.ValueType type) {
            this.value = value;
            this.type = type;
        }
    }
}
