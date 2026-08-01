/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

import dezz.status.widget.sprut.SprutPath;

/** Durable mapping from one vehicle metric to one Sprut.hub characteristic. */
public final class CarSprutBinding {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_MIN_INTERVAL_MS = 3_600_000L;
    private static final int MAX_SIGNATURE_CHARS = 1_024;
    private static final int MAX_NAME_CHARS = 512;

    /** Policy is mandatory whenever a numeric sample targets an integer characteristic. */
    public enum IntegerPolicy {
        /** Reject a fractional result. No silent truncation is permitted. */
        EXACT,
        /** Round to the nearest integer, resolving an exact half away from zero. */
        ROUND_HALF_UP;

        @NonNull static IntegerPolicy parse(String raw) {
            String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) return EXACT;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unknown integer policy: " + raw, error);
            }
        }
    }

    @NonNull public final String metricId;
    public final boolean enabled;
    @NonNull public final SprutPath targetPath;
    /** Signature of type/format/constraints at selection time; names are intentionally excluded. */
    @NonNull public final String targetSnapshotSignature;
    @NonNull public final String targetAccessoryName;
    @NonNull public final String targetServiceName;
    @NonNull public final String targetCharacteristicName;
    public final double scale;
    public final double offset;
    @NonNull public final IntegerPolicy integerPolicy;
    /** Minimum delay between successful writes for this mapping. Zero disables throttling. */
    public final long minIntervalMs;

    public CarSprutBinding(@NonNull String metricId, boolean enabled,
                           @NonNull SprutPath targetPath, String targetSnapshotSignature,
                           String targetAccessoryName, String targetServiceName,
                           String targetCharacteristicName, double scale, double offset,
                           @NonNull IntegerPolicy integerPolicy, long minIntervalMs) {
        this.metricId = CarTelemetryValue.requireMetricId(metricId);
        this.enabled = enabled;
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath");
        this.targetSnapshotSignature = bounded(targetSnapshotSignature, MAX_SIGNATURE_CHARS,
                "target snapshot signature");
        this.targetAccessoryName = bounded(targetAccessoryName, MAX_NAME_CHARS,
                "target accessory name");
        this.targetServiceName = bounded(targetServiceName, MAX_NAME_CHARS,
                "target service name");
        this.targetCharacteristicName = bounded(targetCharacteristicName, MAX_NAME_CHARS,
                "target characteristic name");
        if (!Double.isFinite(scale) || !Double.isFinite(offset)) {
            throw new IllegalArgumentException("Scale and offset must be finite");
        }
        if (minIntervalMs < 0L || minIntervalMs > MAX_MIN_INTERVAL_MS) {
            throw new IllegalArgumentException("minIntervalMs must be between 0 and "
                    + MAX_MIN_INTERVAL_MS);
        }
        this.scale = scale;
        this.offset = offset;
        this.integerPolicy = Objects.requireNonNull(integerPolicy, "integerPolicy");
        this.minIntervalMs = minIntervalMs;
    }

    @NonNull
    public static CarSprutBinding create(@NonNull String metricId, @NonNull SprutPath targetPath) {
        return new CarSprutBinding(metricId, true, targetPath, "", "", "", "",
                1d, 0d, IntegerPolicy.EXACT, 1_000L);
    }

    @NonNull
    public static CarSprutBinding fromJson(@NonNull JSONObject object) {
        int schema = object.optInt("schema", SCHEMA_VERSION);
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported car/Sprut binding schema: " + schema);
        }
        SprutPath path = SprutPath.parse(object.optString("targetPath", ""));
        double scale = number(object, "scale", 1d);
        double offset = number(object, "offset", 0d);
        long minIntervalMs = longNumber(object, "minIntervalMs", 1_000L);
        return new CarSprutBinding(
                object.optString("metricId", ""),
                object.optBoolean("enabled", true),
                path,
                object.optString("targetSnapshotSignature", ""),
                object.optString("targetAccessoryName", ""),
                object.optString("targetServiceName", ""),
                object.optString("targetCharacteristicName", ""),
                scale,
                offset,
                IntegerPolicy.parse(object.optString("integerPolicy", IntegerPolicy.EXACT.name())),
                minIntervalMs);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schema", SCHEMA_VERSION);
        object.put("metricId", metricId);
        object.put("enabled", enabled);
        object.put("targetPath", targetPath.stableId());
        object.put("targetSnapshotSignature", targetSnapshotSignature);
        object.put("targetAccessoryName", targetAccessoryName);
        object.put("targetServiceName", targetServiceName);
        object.put("targetCharacteristicName", targetCharacteristicName);
        object.put("scale", scale);
        object.put("offset", offset);
        object.put("integerPolicy", integerPolicy.name());
        object.put("minIntervalMs", minIntervalMs);
        return object;
    }

    @NonNull
    private static String bounded(String raw, int maxChars, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxChars || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static double number(JSONObject object, String key, double fallback) {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return ((Number) raw).doubleValue();
    }

    private static long longNumber(JSONObject object, String key, long fallback) {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        Number value = (Number) raw;
        double decimal = value.doubleValue();
        long result = value.longValue();
        if (!Double.isFinite(decimal) || decimal != (double) result) {
            throw new IllegalArgumentException(key + " must be an exact integer");
        }
        return result;
    }
}
