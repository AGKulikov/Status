/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class RuntimeSnapshotPreferencesTest {
    @Test public void thousandPositionPacketsStayLiveAndCheckpointOnlyNewest() {
        Fixture f = new Fixture();
        for (int i = 0; i < 1000; i++) f.store.edit().putLong("position", i)
                .putLong("observed", 100_000L + i).apply();
        assertEquals(999L, f.store.getLong("position", -1));
        assertEquals(100_999L, f.store.getLong("observed", -1));
        assertEquals(1, f.tasks.size());
        assertEquals(0, f.commits);
        f.tasks.remove(0).run();
        assertEquals(1, f.commits);
        assertEquals(999L, f.disk.get("position"));
        assertTrue(f.tasks.isEmpty());
    }

    @Test public void blockedDiskDoesNotBlockReadsOrAccumulateOldSnapshots() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.beforeCommit = () -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("test release missing"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        f.store.edit().putString("track", "first").apply();
        Runnable checkpoint = f.tasks.remove(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> { try { checkpoint.run(); } catch (Throwable e) { failure.set(e); } });
        writer.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) f.store.edit().putLong("position", i).apply();
            f.store.edit().putString("track", "newest").apply();
            assertEquals("newest", f.store.getString("track", ""));
            assertEquals(999L, f.store.getLong("position", -1));
            assertTrue(f.tasks.isEmpty());
        } finally { release.countDown(); writer.join(3000); }
        assertFalse(writer.isAlive());
        assertNull(failure.get());
        assertEquals(1, f.tasks.size());
        f.beforeCommit = () -> {};
        f.tasks.remove(0).run();
        assertEquals("newest", f.disk.get("track"));
        assertEquals(999L, f.disk.get("position"));
        assertTrue(f.tasks.isEmpty());
    }

    @Test public void failedCheckpointRetriesLatestWithoutRollingBackLiveValues() {
        Fixture f = new Fixture();
        f.succeed = false;
        f.store.edit().putString("address", "phone-a").putInt("battery", 20).apply();
        f.tasks.remove(0).run();
        assertEquals(1, f.tasks.size());
        assertEquals(20, f.store.getInt("battery", -1));
        f.store.edit().clear().putString("address", "phone-b").putInt("battery", 70).apply();
        f.succeed = true;
        f.tasks.remove(0).run();
        assertEquals("phone-b", f.disk.get("address"));
        assertEquals(70, f.disk.get("battery"));
        assertTrue(f.tasks.isEmpty());
        assertFalse(f.messages.isEmpty());
    }

    @Test public void clearAndDefensiveSetsCannotResurrectAnOldRecord() {
        Fixture f = new Fixture();
        Set<String> tags = new HashSet<>(); tags.add("one");
        f.store.edit().putStringSet("tags", tags).putLong("old_observation", 5L).apply();
        tags.add("external-mutation");
        f.store.getStringSet("tags", null).add("reader-mutation");
        assertEquals(1, f.store.getStringSet("tags", null).size());
        f.store.edit().clear().putLong("new_boot", 6L).apply();
        f.tasks.remove(0).run();
        assertEquals(1, f.disk.size());
        assertEquals(6L, f.disk.get("new_boot"));
        f.store.edit().putLong("new_boot", 6L).apply();
        assertTrue(f.tasks.isEmpty());
    }

    private static final class Fixture {
        final Map<String, Object> disk = new HashMap<>();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        boolean succeed = true;
        int commits;
        Runnable beforeCommit = () -> {};
        final SharedPreferences backing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return new HashMap<>(disk);
                    if (method.getName().equals("edit")) return editor();
                    throw new AssertionError("Unexpected disk read " + method.getName());
                });
        final RuntimeSnapshotPreferences store = new RuntimeSnapshotPreferences(
                backing, tasks::add, Runnable::run, messages::add);

        SharedPreferences.Editor editor() {
            Map<String, Object> next = new HashMap<>(disk);
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "apply": throw new AssertionError("Framework QueuedWork must not be used");
                            case "clear": next.clear(); return proxy;
                            case "remove": next.remove(args[0]); return proxy;
                            case "commit":
                                beforeCommit.run(); commits++;
                                if (succeed) { disk.clear(); disk.putAll(next); }
                                return succeed;
                            default:
                                if (method.getName().startsWith("put")) { next.put((String) args[0], args[1]); return proxy; }
                                throw new AssertionError(method.getName());
                        }
                    });
        }
    }
}
