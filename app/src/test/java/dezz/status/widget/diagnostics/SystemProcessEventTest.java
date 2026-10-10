/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class SystemProcessEventTest {
    @Test public void navigationAndMusicStartWithoutPrivateArguments() {
        assertEquals("event=process_start, package=ru.yandex.yandexnavi, pid=71",
                SystemProcessEvent.parse("Start proc 71:ru.yandex.yandexnavi/u0a1 for activity secret=secret"));
        assertEquals("event=process_died, package=ru.yandex.music:player, pid=72",
                SystemProcessEvent.parse("Process ru.yandex.music:player (pid 72) has died: private"));
        assertNull(SystemProcessEvent.parse("Start proc 71:ru.yandex.music.fake/u0a1"));
    }
    @Test public void lifecycleFactsExcludeIntentExtrasAndKillReason() {
        assertEquals("event=process_start, package=ecarx.xsf.inputservice, pid=789",SystemProcessEvent.parse("ActivityManager: Start proc 789:ecarx.xsf.inputservice/u0a5 for service extras secret=value"));
        assertEquals("event=process_died, package=com.ecarx.dimmenu, pid=123",SystemProcessEvent.parse("Process com.ecarx.dimmenu (pid 123) has died: foreground TOP"));
        String event=SystemProcessEvent.parse("Killing 345:ru.natro.statuswidget:hud/u0a2 (adj 900): private reason");
        assertTrue(event.contains("process_killed"));assertFalse(event.contains("private"));
        assertTrue(SystemProcessEvent.parse("ANR in com.ecarx.hud (secret detail)").contains("event=anr"));
    }
    @Test public void unrelatedProcessesAndPackagePrefixCollisionsAreIgnored() {
        assertNull(SystemProcessEvent.parse("Start proc 123:com.ecarx.hud.fake/u0a1"));
        assertNull(SystemProcessEvent.parse("Process another.app (pid 123) has died"));
        assertNull(SystemProcessEvent.parse("START u0 Intent { cmp=com.ecarx.hud/.Main text=private }"));
    }
}
