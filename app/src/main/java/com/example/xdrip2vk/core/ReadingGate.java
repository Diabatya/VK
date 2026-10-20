package com.example.xdrip2vk.core;

/**
 * Валидация свежести показания. Пороги едины для всех входных каналов
 * (broadcast, HTTP-опрос, ручная отправка).
 */
public final class ReadingGate {

    public static final long MAX_AGE_MS = 15 * 60 * 1000L;        // 15 минут
    public static final long MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L; // допуск на рассинхрон часов

    public enum Verdict {FRESH, STALE, FUTURE, INVALID}

    private ReadingGate() {}

    public static Verdict check(long timestampMs, long nowMs) {
        if (timestampMs <= 0) {
            return Verdict.INVALID;
        }
        if (timestampMs > nowMs + MAX_FUTURE_SKEW_MS) {
            return Verdict.FUTURE;
        }
        if (nowMs - timestampMs > MAX_AGE_MS) {
            return Verdict.STALE;
        }
        return Verdict.FRESH;
    }
}
