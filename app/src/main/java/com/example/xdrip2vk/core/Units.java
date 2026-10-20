package com.example.xdrip2vk.core;

/**
 * Внутренние единицы приложения — мг/дл (так отдаёт xDrip и broadcast, и sgv.json).
 * Отображение и пороги в настройках могут быть в ммоль/л — конверсия только на границах.
 */
public final class Units {
    public static final double MGDL_PER_MMOL = 18.0;

    private Units() {}

    public static double mmolToMgdl(double mmol) {
        return mmol * MGDL_PER_MMOL;
    }

    public static double mgdlToMmol(double mgdl) {
        return mgdl / MGDL_PER_MMOL;
    }
}
