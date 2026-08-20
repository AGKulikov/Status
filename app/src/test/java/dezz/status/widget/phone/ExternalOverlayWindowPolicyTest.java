/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalOverlayWindowPolicyTest {
    @Test public void distinctCameraWindowIsCandidate() {
        assertTrue(ExternalOverlayWindowPolicy.isCandidate("ru.natro.statuswidget",
                "ru.yandex.yandexnavi", "com.ecarx.avm.camera"));
    }

    @Test public void ownForegroundAndFrameworkWindowsAreIgnored() {
        assertFalse(ExternalOverlayWindowPolicy.isCandidate("ru.natro.statuswidget",
                "ru.yandex.yandexnavi", "ru.natro.statuswidget"));
        assertFalse(ExternalOverlayWindowPolicy.isCandidate("ru.natro.statuswidget",
                "ru.yandex.yandexnavi", "ru.yandex.yandexnavi"));
        assertFalse(ExternalOverlayWindowPolicy.isCandidate("ru.natro.statuswidget",
                "ru.yandex.yandexnavi", "com.android.systemui"));
        assertFalse(ExternalOverlayWindowPolicy.isCandidate("ru.natro.statuswidget",
                "ru.yandex.yandexnavi", "com.google.android.inputmethod.latin"));
    }
}
