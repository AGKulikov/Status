/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class SprutHubSelectionTest {
    @Test public void configuredSerialSkipsOldOfflineDuplicate() throws Exception {
        JSONObject oldSession = hub("C2820438BC2E68FA", false, 1781451141000L);
        JSONObject liveSession = hub("C2820438BC2E68FA", true, 1784567000000L);
        assertSame(liveSession, SprutHubSelection.select(
                new JSONArray().put(oldSession).put(liveSession), "C2820438BC2E68FA"));
    }

    @Test public void configuredSerialMatchingIsCaseInsensitive() throws Exception {
        JSONObject liveSession = hub("C2820438BC2E68FA", true, 1L);
        assertSame(liveSession, SprutHubSelection.select(
                new JSONArray().put(liveSession), "c2820438bc2e68fa"));
    }

    @Test public void blankSerialPrefersOnlineRow() throws Exception {
        JSONObject oldSession = hub("OLD", false, 200L);
        JSONObject liveSession = hub("LIVE", true, 100L);
        assertSame(liveSession, SprutHubSelection.select(
                new JSONArray().put(oldSession).put(liveSession), ""));
    }

    @Test public void newestOnlineDuplicateWinsAfterFullScan() throws Exception {
        JSONObject older = hub("SERIAL", true, 100L);
        JSONObject newer = hub("SERIAL", true, 200L);
        assertSame(newer, SprutHubSelection.select(
                new JSONArray().put(older).put(newer), "SERIAL"));
    }

    @Test public void unknownPresenceBeatsExplicitOffline() throws Exception {
        JSONObject offline = hub("SERIAL", false, 200L);
        JSONObject unknown = new JSONObject().put("serial", "SERIAL").put("lastSeen", 100L);
        assertSame(unknown, SprutHubSelection.select(
                new JSONArray().put(offline).put(unknown), "SERIAL"));
        assertEquals(SprutHubSelection.Presence.UNKNOWN,
                SprutHubSelection.presenceOf(unknown));
    }

    @Test public void onlyOfflineRowRemainsProbeCandidate() throws Exception {
        JSONObject offline = hub("SERIAL", false, 200L);
        assertSame(offline, SprutHubSelection.select(new JSONArray().put(offline), "SERIAL"));
    }

    @Test public void missingConfiguredHubReturnsNull() throws Exception {
        assertNull(SprutHubSelection.select(
                new JSONArray().put(hub("OTHER", true, 1L)), "EXPECTED"));
    }

    private static JSONObject hub(String serial, boolean online, long lastSeen) throws Exception {
        return new JSONObject().put("serial", serial).put("online", online)
                .put("lastSeen", lastSeen);
    }
}
