/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gates for independent page/journal scrolling on the ECARX Android 9 screen. */
public final class Ha1163PhoneSettingsScrollContractTest {
    @Test public void journalUpdatesNeverFocusAndPullTheSettingsPageDown() throws Exception {
        String settings = source("PhoneConnectorSettingsActivity.java");

        assertTrue(settings.contains("rendered.contentEquals(connectionJournal.getText())"));
        assertTrue(settings.contains("scrollConnectionJournalToBottomWithoutFocus"));
        assertTrue(settings.contains("connectionJournalScroll.scrollTo("));
        assertFalse(settings.contains("connectionJournalScroll.fullScroll("));
    }

    @Test public void journalOwnsDragsUntilItsTopOrBottomEdge() throws Exception {
        String settings = source("PhoneConnectorSettingsActivity.java");

        assertTrue(settings.contains("connectionJournalScroll = new NestedScrollView(this)"));
        assertTrue(settings.contains("connectionJournalScroll.setNestedScrollingEnabled(true)"));
        assertTrue(settings.contains("installConnectionJournalTouchRouting()"));
        assertTrue(settings.contains("MotionEvent.ACTION_DOWN"));
        assertTrue(settings.contains("view.canScrollVertically(direction)"));
        assertTrue(settings.contains("requestDisallowInterceptTouchEvent("));
    }

    @Test public void releaseIdentityIsHa1164() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) {
            build = project("../build.gradle");
        }
        assertTrue(build.contains("return 'v2.8.2-ha1190'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
