package ru.natro.ancstest;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReconnectBackoffTest {
    @Test
    public void growsAndCapsAtOneMinute() {
        ReconnectBackoff backoff = new ReconnectBackoff();

        assertEquals(1_000L, backoff.nextDelayMs());
        assertEquals(2_000L, backoff.nextDelayMs());
        assertEquals(5_000L, backoff.nextDelayMs());
        assertEquals(10_000L, backoff.nextDelayMs());
        assertEquals(30_000L, backoff.nextDelayMs());
        assertEquals(60_000L, backoff.nextDelayMs());
        assertEquals(60_000L, backoff.nextDelayMs());
    }

    @Test
    public void readyConnectionResetsTheSequence() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        backoff.nextDelayMs();
        backoff.nextDelayMs();

        backoff.reset();

        assertEquals(0, backoff.attempt());
        assertEquals(1_000L, backoff.nextDelayMs());
    }
}
