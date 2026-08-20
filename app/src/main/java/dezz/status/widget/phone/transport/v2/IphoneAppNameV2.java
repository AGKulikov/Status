/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Session-scoped ANCS app display-name resolution. */
public final class IphoneAppNameV2 {
    public final String appIdentifier;
    public final String appName;
    public final long observedAtElapsedMillis;

    public IphoneAppNameV2(String appIdentifier, String appName,
                           long observedAtElapsedMillis) {
        this.appIdentifier = safe(appIdentifier).trim();
        this.appName = safe(appName).trim();
        if (this.appIdentifier.isEmpty()) {
            throw new IllegalArgumentException("appIdentifier is required");
        }
        if (observedAtElapsedMillis < 0L) {
            throw new IllegalArgumentException("negative observation time");
        }
        this.observedAtElapsedMillis = observedAtElapsedMillis;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
