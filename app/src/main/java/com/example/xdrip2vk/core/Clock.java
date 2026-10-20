package com.example.xdrip2vk.core;

/**
 * Подменяемый источник времени для координатора доставки.
 * В приложении — реальные часы, в тестах — управляемый FakeClock.
 */
public interface Clock {
    long now();

    Clock SYSTEM = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };
}
