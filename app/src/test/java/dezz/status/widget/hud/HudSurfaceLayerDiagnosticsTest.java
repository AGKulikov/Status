/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudSurfaceLayerDiagnosticsTest {
    @Test public void enabledMaskRequiresBothLiveLayers() {
        String dump = "status_widget_hud_d2_mask#0\nstatus_widget_hud_d2_content#0\n";
        HudSurfaceLayerDiagnostics.Result result = HudSurfaceLayerDiagnostics.inspect(
                dump, null, "status_widget_hud_d2", true);
        assertTrue(result.maskFound);
        assertTrue(result.contentFound);
        assertTrue(result.complete());
        assertTrue(result.detail().contains("маска ВКЛ"));
        assertTrue(result.detail().contains("mask/content"));
    }

    @Test public void missingMaskIsReportedInsteadOfClaimingSuccess() {
        HudSurfaceLayerDiagnostics.Result result = HudSurfaceLayerDiagnostics.inspect(
                "status_widget_hud_d2_content#0", null, "status_widget_hud_d2", true);
        assertFalse(result.complete());
        assertTrue(result.detail().contains("слой mask не найден"));
    }

    @Test public void disabledMaskOnlyRequiresContent() {
        HudSurfaceLayerDiagnostics.Result result = HudSurfaceLayerDiagnostics.inspect(
                "status_widget_hud_d2_content#0", null, "status_widget_hud_d2", false);
        assertTrue(result.complete());
        assertTrue(result.detail().contains("маска ВЫКЛ"));
    }
}
