/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class AnrLogContextTest {
    @Test public void associatesReasonOnlyWithSameLoggerThreadAndTime() {
        AnrLogContext c=new AnrLogContext();
        assertNotNull(c.accept("100.0 123 456 E ActivityManager: ANR in ru.yandex.yandexnavi"));
        assertTrue(c.accept("100.1 123 456 E ActivityManager: Reason: Input dispatching timed out").contains("Input dispatching timed out"));
        assertNull(c.accept("100.1 123 457 E ActivityManager: Reason: unrelated"));
        assertNull(c.accept("110.1 123 456 E ActivityManager: Reason: stale"));
        assertNull(c.accept("100.1 123 456 E ActivityManager: private unrelated event"));
    }
    @Test public void unrelatedAnrEndsPreviousContext() {
        AnrLogContext c=new AnrLogContext();
        c.accept("100.0 123 456 E ActivityManager: ANR in ru.yandex.yandexnavi");
        c.accept("101.0 123 456 E ActivityManager: ANR in unrelated.app");
        assertNull(c.accept("101.1 123 456 E ActivityManager: Reason: unrelated"));
    }
}
