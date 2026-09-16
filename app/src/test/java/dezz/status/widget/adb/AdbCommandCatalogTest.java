/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public final class AdbCommandCatalogTest {
    @Test public void modesNeverInventAnOffReading() {
        assertEquals(0, AdbCommandCatalog.persistentMode("0", "off"));
        assertEquals(1, AdbCommandCatalog.persistentMode("0", "on"));
        assertEquals(2, AdbCommandCatalog.persistentMode("1", "on"));
        assertEquals(-1, AdbCommandCatalog.persistentMode("", ""));
        assertEquals(-1, AdbCommandCatalog.persistentMode("7", "on"));
    }
    @Test public void onlyInstalledGpsAreChanged() {
        List<AdbCommandCatalog.Entry> entries = AdbCommandCatalog.entries(pkg -> pkg.equals("usb.gps"), false);
        assertTrue(entries.stream().anyMatch(e -> e.title.equals("UsbGps4Droid MOD")));
        assertFalse(entries.stream().anyMatch(e -> e.title.equals("GNSSme")));
        String reset = entries.stream().filter(e -> e.title.startsWith("RESET")).findFirst().get().command;
        assertTrue(reset.contains("usb.gps android:mock_location deny"));
        assertFalse(reset.contains("ru.monjaro.gnssme"));
        assertTrue(entries.stream().anyMatch(e -> e.command.equals("pi")));
        assertTrue(AdbCommandCatalog.entries(pkg -> false, true).stream().anyMatch(e -> e.command.equals("od")));
        assertEquals(9, AdbCommandCatalog.GPS.length);
    }
    @Test public void appendPreservesExistingAndCanonicalizesShortNames() {
        assertEquals("one.app/one.app.Service:two.app/two.app.Service", AdbCommandCatalog.appendService("one.app/.Service", "two.app/.Service"));
        assertEquals("one.app/one.app.Service", AdbCommandCatalog.appendService("one.app/.Service:one.app/one.app.Service", "one.app/.Service"));
        assertEquals("one.app/one.app.Service", AdbCommandCatalog.appendService("null", "one.app/.Service"));
        assertEquals("", AdbCommandCatalog.canonicalComponent("one.app/.Service;reboot"));
    }
    @Test public void ourListenerUsesTheRealManifestClass() {
        String own = AdbCommandCatalog.entries(p -> false, false).stream().filter(e -> e.title.equals("Natro")).findFirst().get().command;
        assertTrue(own.contains("ru.natro.statuswidget/dezz.status.widget.MediaNotificationListener"));
    }
}
