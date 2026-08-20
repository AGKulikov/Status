package dezz.status.widget.driver;

public final class DriverClimateIgnitionPolicy {
    public static final long UNKNOWN = 2097409L;
    public static final long LOCK = 2097410L;
    public static final long OFF = 2097411L;
    public static final long ACC = 2097412L;
    public static final long ON = 2097413L;
    public static final long START = 2097414L;
    public static final long DRIVE = 2097415L;

    public enum State {
        UNKNOWN,
        OFF,
        ACTIVE
    }

    private DriverClimateIgnitionPolicy() {
    }

    public static State fromRaw(double value) {
        if (!Double.isFinite(value)) return State.UNKNOWN;
        long state = Math.round(value);
        if (state == LOCK || state == OFF) return State.OFF;
        if (state == ACC || state == ON || state == START || state == DRIVE) {
            return State.ACTIVE;
        }
        return State.UNKNOWN;
    }
}
