package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PhoneBluetoothIndicatorPolicyTest {
    @Test public void ordinaryBluetoothWithoutSelectedPhoneKeepsDefaultGlyph() {
        assertEquals(PhoneBluetoothIndicatorPolicy.Appearance.DEFAULT,
                PhoneBluetoothIndicatorPolicy.resolve(true, false, false));
    }

    @Test public void phoneAndMusicConnectionUsesCleanBlueOutline() {
        assertEquals(PhoneBluetoothIndicatorPolicy.Appearance.PHONE_OUTLINE,
                PhoneBluetoothIndicatorPolicy.resolve(true, true, false));
    }

    @Test public void ActualNotificationDeliveryFillsTheSameGlyph() {
        assertEquals(PhoneBluetoothIndicatorPolicy.Appearance.PHONE_SOLID,
                PhoneBluetoothIndicatorPolicy.resolve(true, true, true));
    }

    @Test public void noClassicConnectionCanNeverClaimNotificationReadiness() {
        assertEquals(PhoneBluetoothIndicatorPolicy.Appearance.DEFAULT,
                PhoneBluetoothIndicatorPolicy.resolve(false, true, true));
    }
}
