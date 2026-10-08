package dezz.status.widget.hud;

import org.junit.Test;
import static org.junit.Assert.*;

public class HudTransientVisibilityTest {
    @Test public void optInRoundTripsAndNeverHidesEditor() throws Exception {
        HudPanelConfig panel = new HudPanelConfig();
        for (HudElementType type : HudElementType.values()) {
            HudElementConfig item = new HudElementConfig("test", type);
            assertTrue(HudTransientVisibility.visible(item, panel, false, true));
            item.options.put("hideDuringVolume", true);
            item = HudElementConfig.fromJson(item.toJson(), 200, 100);
            assertFalse(HudTransientVisibility.visible(item, panel, false, true));
            assertTrue(HudTransientVisibility.visible(item, panel, false, false));
            assertTrue(HudTransientVisibility.visible(item, panel, true, true));
            assertTrue(item.copy().options.optBoolean("hideDuringVolume"));
        }
    }
    @Test public void usesExistingVolumeWindowIncludingExtensionAndReset() throws Exception {
        HudVolumeVisibility volume = new HudVolumeVisibility();
        assertFalse(volume.sample(10, 1000));
        assertFalse(volume.visible(1000));
        assertTrue(volume.sample(11, 1100));
        assertTrue(volume.visible(3099));
        assertTrue(volume.sample(12, 3000));
        assertTrue(volume.visible(4999));
        assertFalse(volume.visible(5000));
        volume.reset();
        assertFalse(volume.visible(3001));
    }
    @Test public void hidingGroupHidesItsMembersOnly() throws Exception {
        HudPanelConfig panel = new HudPanelConfig();
        HudElementConfig group = new HudElementConfig("group", HudElementType.HORIZONTAL_GROUP);
        group.options.put("hideDuringVolume", true);
        HudHorizontalGroup.setMemberIds(group, java.util.Arrays.asList("cover"));
        panel.elements.add(group);
        assertFalse(HudTransientVisibility.visible(new HudElementConfig("cover", HudElementType.MEDIA_ARTWORK), panel, false, true));
        assertTrue(HudTransientVisibility.visible(new HudElementConfig("clock", HudElementType.CLOCK), panel, false, true));
    }
}
