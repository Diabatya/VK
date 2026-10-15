package com.example.xdrip2vk.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Разбор времени из полей xDrip/Nightscout JSON.
 * Явно различает единицы: миллисекунды, секунды (epoch) и строки ISO-8601.
 * Никогда не подставляет текущее время: невалидное значение → -1.
 */
public final class TimeFields {

    // epoch ms сейчас ~1.7e12, epoch секунды ~1.7e9. Порог 1e11 разделяет их однозначно.
    private static final double SECONDS_MS_THRESHOLD = 1e11;

    private TimeFields() {}

    /** @return epoch ms или -1, если значение отсутствует/неразборчиво. */
    public static long toEpochMs(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number) {
            return normalizeNumber(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) {
                return -1;
            }
            try {
                return normalizeNumber(Double.parseDouble(s));
            } catch (NumberFormatException ignore) {
                return parseIso(s);
            }
        }
        return -1;
    }

    private static long normalizeNumber(double v) {
        if (!Double.isFinite(v) || v <= 0) {
            return -1;
        }
        if (v >= SECONDS_MS_THRESHOLD) {
            return (long) v;
        }
        return (long) (v * 1000.0);
    }

    private static long parseIso(String s) {
        String t = s.trim();
        // "…+0000" → "…+00:00": xDrip отдаёт offset без двоеточия, OffsetDateTime ждёт с двоеточием
        if (t.length() > 5 && t.matches(".*[+-]\\d{4}$")) {
            t = t.substring(0, t.length() - 2) + ":" + t.substring(t.length() - 2);
        }
        try {
            return Instant.parse(t).toEpochMilli();
        } catch (DateTimeParseException ignore) {
        }
        try {
            return OffsetDateTime.parse(t).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
        }
        try {
            return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignore) {
        }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignore) {
        }
        return -1;
    }
}
