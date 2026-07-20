/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import java.util.Objects;

/** Stable address of one Sprut.hub characteristic. */
public final class SprutPath implements Comparable<SprutPath> {
    private final long accessoryId;
    private final long serviceId;
    private final long characteristicId;

    public SprutPath(long accessoryId, long serviceId, long characteristicId) {
        if (accessoryId < 0 || serviceId < 0 || characteristicId < 0) {
            throw new IllegalArgumentException("Sprut.hub ids must not be negative");
        }
        this.accessoryId = accessoryId;
        this.serviceId = serviceId;
        this.characteristicId = characteristicId;
    }

    public long accessoryId() { return accessoryId; }

    public long serviceId() { return serviceId; }

    public long characteristicId() { return characteristicId; }

    /** Compact, human-readable value suitable for preferences and logs. */
    public String stableId() {
        return accessoryId + "/" + serviceId + "/" + characteristicId;
    }

    /**
     * Accepts the canonical {@code aId/sId/cId} form and the legacy underscore or colon forms.
     */
    public static SprutPath parse(String raw) {
        String value = Objects.requireNonNull(raw, "raw").trim();
        String[] parts = value.split("[/_:]", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected aId/sId/cId, got: " + raw);
        }
        try {
            return new SprutPath(
                    Long.parseLong(parts[0].trim()),
                    Long.parseLong(parts[1].trim()),
                    Long.parseLong(parts[2].trim()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid Sprut.hub path: " + raw, error);
        }
    }

    @Override public int compareTo(SprutPath other) {
        int result = Long.compare(accessoryId, other.accessoryId);
        if (result == 0) result = Long.compare(serviceId, other.serviceId);
        if (result == 0) result = Long.compare(characteristicId, other.characteristicId);
        return result;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SprutPath)) return false;
        SprutPath path = (SprutPath) other;
        return accessoryId == path.accessoryId
                && serviceId == path.serviceId
                && characteristicId == path.characteristicId;
    }

    @Override public int hashCode() {
        return Objects.hash(accessoryId, serviceId, characteristicId);
    }

    @Override public String toString() { return stableId(); }
}
