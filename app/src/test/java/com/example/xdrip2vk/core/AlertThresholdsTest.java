package com.example.xdrip2vk.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class AlertThresholdsTest {

    @Test
    public void equivalentReadingsClassifyIdentically() {
        // Контрольный пример из задания: 54 мг/дл и 3,0 ммоль/л — оба «низкий сахар»
        AlertThresholds t = AlertThresholds.parseMmol("4.1", "12.2");
        assertEquals(AlertThresholds.Level.LOW, t.classify(54.0));
        assertEquals(AlertThresholds.Level.LOW, t.classify(Units.mmolToMgdl(3.0)));

        // и в норме — вне зависимости от единиц отображения
        assertEquals(AlertThresholds.Level.NORMAL, t.classify(Units.mmolToMgdl(6.0)));
        assertEquals(AlertThresholds.Level.HIGH, t.classify(Units.mmolToMgdl(13.0)));
    }

    @Test
    public void decimalCommaAccepted() {
        AlertThresholds t = AlertThresholds.parseMmol("4,1", "12,2");
        assertEquals(AlertThresholds.Level.LOW, t.classify(54.0));
        assertEquals(4.1, AlertThresholds.parseMmolValue("4,1"), 0.0001);
    }

    @Test
    public void invalidThresholdsRejected() {
        String[] bad = {"abc", "", " ", "-1", "0.1", "100", "NaN", "Infinity", "4.1.2"};
        for (String s : bad) {
            try {
                AlertThresholds.parseMmolValue(s);
                fail("should reject: " + s);
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            AlertThresholds.parseMmol("5.0", "4.0");
            fail("min >= max must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            AlertThresholds.parseMmol("5.0", "5.0");
            fail("min == max must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void invalidThresholdsFallBackToDefaults() {
        AlertThresholds t = AlertThresholds.orDefault("junk", "junk");
        assertEquals(AlertThresholds.Level.LOW, t.classify(54.0));
        assertEquals(AlertThresholds.Level.NORMAL, t.classify(100.0));
    }
}
