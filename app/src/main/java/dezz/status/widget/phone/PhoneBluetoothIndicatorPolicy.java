package dezz.status.widget.phone;

/** Selects one unambiguous Bluetooth glyph for the current end-to-end phone state. */
public final class PhoneBluetoothIndicatorPolicy {
    public enum Appearance {
        DEFAULT,
        PHONE_OUTLINE,
        PHONE_SOLID
    }

    private PhoneBluetoothIndicatorPolicy() {
    }

    public static Appearance resolve(boolean classicBluetoothConnected,
                                     boolean selectedPhoneConfigured,
                                     boolean notificationPathActiveNow) {
        if (!classicBluetoothConnected || !selectedPhoneConfigured) {
            return Appearance.DEFAULT;
        }
        return notificationPathActiveNow
                ? Appearance.PHONE_SOLID
                : Appearance.PHONE_OUTLINE;
    }
}
