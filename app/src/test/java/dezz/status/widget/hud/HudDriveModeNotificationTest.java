/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import dezz.status.widget.car.CarControlState;
import org.junit.Test;
import static org.junit.Assert.*;

public class HudDriveModeNotificationTest {
    private static final int COMFORT = 570491138, ECO = 570491137, SPORT = 570491139;
    static CarControlState state(double code, boolean available, long timestamp) {
        return new CarControlState("vehicle.drive_mode", available, true, code, "", false,
                0, null, timestamp);
    }
    @Test public void firstSampleIsSilentAndDuplicatesDoNotExtendTheDeadline() {
        HudDriveModeNotification model = new HudDriveModeNotification();
        assertFalse(model.accept(state(COMFORT, true, 1), 10));
        assertEquals(-1, model.visibleCode(10));
        assertTrue(model.accept(state(ECO, true, 2), 20));
        assertEquals(ECO, model.visibleCode(3019));
        assertFalse(model.accept(state(ECO, true, 3), 3000));
        assertEquals(-1, model.visibleCode(3020));
    }
    @Test public void quickChangesReplaceTheTextAndRestartThreeSeconds() {
        HudDriveModeNotification model = new HudDriveModeNotification();
        model.accept(state(COMFORT, true, 1), 1);
        model.accept(state(ECO, true, 2), 2);
        assertTrue(model.accept(state(SPORT, true, 3), 1000));
        assertEquals(SPORT, model.visibleCode(3999));
        assertEquals(-1, model.visibleCode(4000));
    }
    @Test public void UnavailableAndInvalidModesClearTheBannerAndReseedSilently() {
        for (double bad : new double[]{255, -1, Double.NaN, Double.POSITIVE_INFINITY,
                COMFORT + .5, 9999}) {
            HudDriveModeNotification model = new HudDriveModeNotification();
            model.accept(state(COMFORT, true, 1), 1);
            model.accept(state(ECO, true, 2), 2);
            assertTrue(model.accept(state(bad, true, 3), 3));
            assertEquals(-1, model.visibleCode(3));
            assertFalse(model.accept(state(SPORT, true, 4), 4));
            assertTrue(model.accept(state(ECO, true, 5), 5));
            assertTrue(model.accept(state(ECO, false, 6), 6));
            assertFalse(model.accept(state(COMFORT, true, 7), 7));
        }
    }
    @Test public void oldSamplesAndOtherControlsCannotReplaceTheCurrentMode() {
        HudDriveModeNotification model = new HudDriveModeNotification();
        model.accept(state(COMFORT, true, 10), 1);
        model.accept(state(ECO, true, 12), 2);
        assertFalse(model.accept(state(SPORT, true, 11), 3));
        assertFalse(model.accept(new CarControlState("other", true, true, SPORT, "",
                false, 0, null, 13), 4));
        assertEquals(ECO, model.visibleCode(4));
        model.reset();
        assertEquals(-1, model.visibleCode(4));
        assertFalse(model.accept(state(SPORT, true, 14), 5));
    }
    @Test public void oldLayoutsEnableTheFeatureAndExplicitOptOutSurvivesBackupJson() throws Exception {
        HudPanelConfig old = HudPanelConfig.fromJson("{\"schema\":6,\"elements\":[]}");
        assertTrue(old.showDriveModeNotification);
        old.showDriveModeNotification = false;
        HudPanelConfig restored = HudPanelConfig.fromJson(old.toJson().toString());
        assertFalse(restored.showDriveModeNotification);
        assertEquals(6, restored.toJson().getInt("schema"));
        assertTrue(restored.elements.isEmpty());
    }
}
