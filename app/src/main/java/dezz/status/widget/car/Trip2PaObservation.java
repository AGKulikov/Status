/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

/** Diagnostic PA observations, separate from the unverified stock Trip-2 telemetry mapping. */
public final class Trip2PaObservation {
    public static final class Field {
        public final int raw;
        public final int availability;
        public final int status;
        public final int format;

        public Field(int raw, int availability, int status, int format) {
            this.raw = raw;
            this.availability = availability;
            this.status = status;
            this.format = format;
        }

        /** This is the tuple seen in the saved firmware captures, not proof of current freshness. */
        public boolean hasObservedEncoding() {
            return raw >= 0 && availability == 1 && status == 0 && format == 0;
        }

        public String metadata() {
            return "availability=" + availability + "; status=" + status + "; format=" + format;
        }
    }

    public final Field distance;
    public final Field elapsed;

    public Trip2PaObservation(Field distance, Field elapsed) {
        this.distance = distance;
        this.elapsed = elapsed;
    }

    /** PA_TS_EDT_time2 advances once per second in 25 saved PCAPs; the old minute unit was wrong. */
    public float elapsedMinutes() {
        return elapsed != null && elapsed.hasObservedEncoding() ? elapsed.raw / 60f : Float.NaN;
    }

    // No distance conversion: every saved capture has constant distance. Neither /10 nor /1000
    // has been established by a simultaneous measurement of the stock display or travelled path.
}
