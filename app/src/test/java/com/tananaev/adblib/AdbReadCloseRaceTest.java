package com.tananaev.adblib;

import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.junit.Assert.*;

public class AdbReadCloseRaceTest {
    @Test(timeout = 5000) public void closeBetweenPollAndReturnPreservesFinalPayload() throws Exception {
        AdbStream stream = new AdbStream(null, 1);
        byte[] payload = "0\nNATRO_EXIT_test:0\n".getBytes("UTF-8");
        Thread closer = new Thread(() -> stream.notifyClose(true));
        ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<byte[]>() {
            @Override public byte[] poll() {
                byte[] value = super.poll();
                if (value != null) {
                    closer.start();
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    while (closer.getState() != Thread.State.BLOCKED
                            && System.nanoTime() < deadline) Thread.yield();
                    assertEquals(Thread.State.BLOCKED, closer.getState());
                }
                return value;
            }
        };
        queue.add(payload);
        Field field = AdbStream.class.getDeclaredField("readQueue");
        field.setAccessible(true);
        field.set(stream, queue);
        assertArrayEquals(payload, stream.read());
        closer.join(2000);
        assertFalse(closer.isAlive());
        try { stream.read(); fail("EOF expected"); } catch (IOException expected) {}
    }

    @Test public void peerCloseDrainsAllQueuedPacketsBeforeEof() throws Exception {
        AdbStream stream = new AdbStream(null, 1);
        byte[] first = {1}, last = {2};
        stream.addPayload(first); stream.addPayload(last); stream.notifyClose(true);
        assertArrayEquals(first, stream.read());
        assertArrayEquals(last, stream.read());
        try { stream.read(); fail("EOF expected"); } catch (IOException expected) {}
    }
}
