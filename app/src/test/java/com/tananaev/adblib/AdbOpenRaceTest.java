package com.tananaev.adblib;

import org.junit.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AdbOpenRaceTest {
    private static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true); f.set(target, value);
    }
    private static AdbConnection connection(boolean accept) throws Exception {
        Constructor<AdbConnection> c = AdbConnection.class.getDeclaredConstructor();
        c.setAccessible(true);
        AdbConnection connection = c.newInstance();
        field(connection, "connectAttempted", true);
        field(connection, "connected", true);
        Field streams = AdbConnection.class.getDeclaredField("openStreams");
        streams.setAccessible(true);
        Map<Integer, AdbStream> map = (Map<Integer, AdbStream>) streams.get(connection);
        connection.outputStream = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes) {
                // Deterministic response BEFORE open() reaches its wait, as a fast adbd can do.
                AdbStream stream = map.values().iterator().next();
                synchronized (stream) {
                    if (accept) { stream.updateRemoteId(7); stream.readyForWrite(); }
                    else stream.notifyClose(true);
                    stream.notifyAll();
                }
            }
        };
        return connection;
    }
    @Test public void immediateOkayCannotBeLost() throws Exception {
        AdbConnection connection = connection(true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { assertNotNull(worker.submit(() -> connection.open("shell:")).get(2, TimeUnit.SECONDS)); }
        finally { worker.shutdownNow(); }
    }
    @Test public void immediateRejectionCannotHangOrBecomeSuccess() throws Exception {
        AdbConnection connection = connection(false);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<AdbStream> result = worker.submit(() -> connection.open("root:"));
            try { result.get(2, TimeUnit.SECONDS); fail("rejected OPEN accepted"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        } finally { worker.shutdownNow(); }
    }
}
