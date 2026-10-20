package com.example.xdrip2vk.core;

/**
 * Неизменяемый снимок одного показания глюкозы.
 * Значение, время и тренд всегда передаются вместе — нельзя взять стрелку от другого показания.
 */
public final class SgvReading {
    public final double sgvMgdl;
    public final long timestampMs;
    public final String direction;
    public final String id;
    public final double carbs;
    public final double insulin;

    public SgvReading(double sgvMgdl, long timestampMs, String direction, String id,
                      double carbs, double insulin) {
        this.sgvMgdl = sgvMgdl;
        this.timestampMs = timestampMs;
        this.direction = direction == null ? "" : direction;
        this.id = id == null ? "" : id;
        this.carbs = carbs;
        this.insulin = insulin;
    }

    public SgvReading(double sgvMgdl, long timestampMs, String direction, String id) {
        this(sgvMgdl, timestampMs, direction, id, 0, 0);
    }
}
