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
                                     boolean selectedPhoneConnected,
                                     boolean notificationDeliveredInCurrentSession) {
        if (!classicBluetoothConnected || !selectedPhoneConnected) {
            return Appearance.DEFAULT;
        }
        return notificationDeliveredInCurrentSession
                ? Appearance.PHONE_SOLID
                : Appearance.PHONE_OUTLINE;
    }
}
