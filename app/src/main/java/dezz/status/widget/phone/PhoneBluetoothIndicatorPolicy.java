package dezz.status.widget.phone;

/** Selects one unambiguous Bluetooth glyph for the current end-to-end phone state. */
public final class PhoneBluetoothIndicatorPolicy {
    public enum Appearance {
        DEFAULT,
        PHONE_MONO
    }

    private PhoneBluetoothIndicatorPolicy() {
    }

    public static Appearance resolve(boolean classicBluetoothConnected,
                                     boolean selectedPhoneConfigured,
                                     boolean notificationPathActiveNow) {
        if (!classicBluetoothConnected || !selectedPhoneConfigured) {
            return Appearance.DEFAULT;
        }
        // Notification readiness remains a data state (and may gate driver rows), but it must
        // not split the Bluetooth rune into separately styled outline/body variants.
        return Appearance.PHONE_MONO;
    }
}
