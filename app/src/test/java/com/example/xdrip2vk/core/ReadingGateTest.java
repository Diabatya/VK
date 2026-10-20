package com.example.xdrip2vk.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReadingGateTest {

    private static final long NOW = 1715336100000L;

    @Test
    public void freshReadingAccepted() {
        assertEquals(ReadingGate.Verdict.FRESH,
                ReadingGate.check(NOW - 60_000, NOW));
        assertEquals(ReadingGate.Verdict.FRESH,
                ReadingGate.check(NOW, NOW));
    }

    @Test
    public void staleReadingRejected() {
        assertEquals(ReadingGate.Verdict.STALE,
                ReadingGate.check(NOW - ReadingGate.MAX_AGE_MS - 1, NOW));
    }

    @Test
    public void staleStaysStaleAcrossPolls() {
        long stale = NOW - 20 * 60 * 1000;
        // повторные опросы не «омолаживают» данные
        for (int i = 0; i < 3; i++) {
            assertEquals(ReadingGate.Verdict.STALE, ReadingGate.check(stale, NOW + i * 60_000));
        }
    }

    @Test
    public void futureRejectedBeyondSkew() {
        assertEquals(ReadingGate.Verdict.FUTURE,
                ReadingGate.check(NOW + ReadingGate.MAX_FUTURE_SKEW_MS + 1000, NOW));
        // в пределах допуска — принимается
        assertEquals(ReadingGate.Verdict.FRESH,
                ReadingGate.check(NOW + 60_000, NOW));
    }

    @Test
    public void invalidTimeRejected() {
        assertEquals(ReadingGate.Verdict.INVALID, ReadingGate.check(0, NOW));
        assertEquals(ReadingGate.Verdict.INVALID, ReadingGate.check(-100, NOW));
    }
}
