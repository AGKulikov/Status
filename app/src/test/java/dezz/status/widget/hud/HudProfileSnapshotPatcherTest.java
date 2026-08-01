package dezz.status.widget.hud;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class HudProfileSnapshotPatcherTest {

    @Test
    public void legacyJsonRouteAlwaysFailsClosed() {
        assertThrows(UnsupportedOperationException.class, () ->
                HudProfileSnapshotPatcher.patchHudAr(
                        "{\"654443008\":\"0\"}", true, 150));
    }
}
