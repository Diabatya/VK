package com.example.xdrip2vk.core;

/**
 * Пороги тревог. В настройках хранятся строками в ммоль/л (исторический формат,
 * ключи не меняются). Сравнение всегда выполняется в мг/дл.
 * Допускается десятичная запятая.
 */
public final class AlertThresholds {

    public static final double MIN_MMOL = 0.5;
    public static final double MAX_MMOL = 50.0;
    public static final String DEFAULT_MIN_STR = "4.1";
    public static final String DEFAULT_MAX_STR = "12.2";

    private static final AlertThresholds DEFAULTS = new AlertThresholds(
            Units.mmolToMgdl(4.1), Units.mmolToMgdl(12.2));

    public enum Level {LOW, NORMAL, HIGH}

    public final double minMgdl;
    public final double maxMgdl;

    private AlertThresholds(double minMgdl, double maxMgdl) {
        this.minMgdl = minMgdl;
        this.maxMgdl = maxMgdl;
    }

    public static AlertThresholds defaults() {
        return DEFAULTS;
    }

    /** Парсит пороги в ммоль/л. Бросает IllegalArgumentException при некорректном вводе. */
    public static AlertThresholds parseMmol(String minStr, String maxStr) {
        double lo = parseMmolValue(minStr);
        double hi = parseMmolValue(maxStr);
        if (!(lo < hi)) {
            throw new IllegalArgumentException("min must be < max");
        }
        return new AlertThresholds(Units.mmolToMgdl(lo), Units.mmolToMgdl(hi));
    }

    /** Парсит один порог в ммоль/л (допускается запятая). */
    public static double parseMmolValue(String s) {
        if (s == null) {
            throw new IllegalArgumentException("empty");
        }
        double v;
        try {
            v = Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number", e);
        }
        if (!Double.isFinite(v) || v < MIN_MMOL || v > MAX_MMOL) {
            throw new IllegalArgumentException("out of range");
        }
        return v;
    }

    /** Невалидные пороги → дефолтные (не ломаем существующие установки). */
    public static AlertThresholds orDefault(String minStr, String maxStr) {
        try {
            return parseMmol(minStr, maxStr);
        } catch (IllegalArgumentException e) {
            return DEFAULTS;
        }
    }

    public Level classify(double mgdl) {
        if (mgdl < minMgdl) {
            return Level.LOW;
        }
        if (mgdl > maxMgdl) {
            return Level.HIGH;
        }
        return Level.NORMAL;
    }
}
