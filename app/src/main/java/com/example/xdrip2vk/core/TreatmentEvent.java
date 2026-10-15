package com.example.xdrip2vk.core;

/**
 * Неизменяемое событие терапии из xDrip/Nightscout treatments.json.
 * id — стабильный идентификатор записи (поле _id/uuid), по нему ведётся дедупликация.
 */
public final class TreatmentEvent {
    public final String id;
    public final long createdAtMs; // 0 — время неизвестно
    public final double carbs;
    public final double insulin;
    public final String eventType;

    public TreatmentEvent(String id, long createdAtMs, double carbs, double insulin, String eventType) {
        this.id = id == null ? "" : id;
        this.createdAtMs = createdAtMs;
        this.carbs = carbs;
        this.insulin = insulin;
        this.eventType = eventType == null ? "" : eventType;
    }

    public boolean hasCarbs() {
        return carbs > 0;
    }

    public boolean hasInsulin() {
        return insulin > 0;
    }
}
