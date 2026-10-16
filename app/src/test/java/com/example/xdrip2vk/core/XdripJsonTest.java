package com.example.xdrip2vk.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class XdripJsonTest {

    @Test
    public void sgvStandardUsesDateFieldMillis() {
        List<SgvReading> out = XdripJson.parseSgv(TestUtil.fixture("sgv_standard.json"));
        assertEquals(2, out.size());
        SgvReading first = out.get(0);
        assertEquals(120.0, first.sgvMgdl, 0.001);
        assertEquals(1715336100000L, first.timestampMs);
        assertEquals("Flat", first.direction);
        assertEquals("fixture-sgv-0002", first.id);
        assertEquals(1715335800000L, out.get(1).timestampMs);
    }

    @Test
    public void sgvSecondsAndIsoAndMissingTime() {
        List<SgvReading> out = XdripJson.parseSgv(TestUtil.fixture("sgv_variants.json"));
        // 4 записи: секунды, ISO-строка — приняты; без времени и с битым временем — отклонены
        assertEquals(2, out.size());
        assertEquals(1715336100000L, out.get(0).timestampMs); // секунды → мс
        assertEquals("SingleUp", out.get(0).direction);
        // ISO-строка dateString → epoch ms
        assertTrue(out.get(1).timestampMs > 0);
    }

    @Test
    public void sgvWithoutTimeRejectedNotNow() {
        long before = System.currentTimeMillis();
        List<SgvReading> out = XdripJson.parseSgv(
                "[{\"sgv\": 140, \"direction\": \"SingleUp\"}]");
        long after = System.currentTimeMillis();
        assertTrue(out.isEmpty());
        // главное: текущее время не подставлялось
        for (SgvReading r : out) {
            assertFalse(r.timestampMs >= before && r.timestampMs <= after);
        }
    }

    @Test
    public void sgvEmptyAndBrokenInput() {
        assertTrue(XdripJson.parseSgv("").isEmpty());
        assertTrue(XdripJson.parseSgv(null).isEmpty());
        assertTrue(XdripJson.parseSgv("not json").isEmpty());
        assertTrue(XdripJson.parseSgv("[]").isEmpty());
    }

    @Test
    public void treatmentsXdripFormat() {
        List<TreatmentEvent> out = XdripJson.parseTreatments(TestUtil.fixture("treatments_xdrip.json"));
        assertEquals(3, out.size());
        TreatmentEvent t3 = out.get(0);
        assertEquals("fixture-treat-3", t3.id);
        assertEquals(1715336700000L, t3.createdAtMs);
        assertEquals(1.5, t3.insulin, 0.001);
        assertFalse(t3.hasCarbs());
        TreatmentEvent t2 = out.get(1);
        assertTrue(t2.hasCarbs());
        assertTrue(t2.hasInsulin());
        // два события с одинаковым временем оба распознаются
        assertEquals(out.get(1).createdAtMs, out.get(2).createdAtMs);
    }

    @Test
    public void treatmentsNightscoutIsoCreatedAt() {
        List<TreatmentEvent> out = XdripJson.parseTreatments(
                TestUtil.fixture("treatments_nightscout_iso.json"));
        assertEquals(2, out.size());
        assertEquals("fixture-ns-1", out.get(0).id);
        assertTrue(out.get(0).createdAtMs > 0);
        assertEquals(60, out.get(0).carbs, 0.001);
        assertEquals(10, out.get(1).carbs, 0.001);
    }

    @Test
    public void treatmentWithoutIdGetsDeterministicSynthId() {
        String json = "[{\"created_at\": 1715336400000, \"carbs\": 20, \"insulin\": 0, \"eventType\": \"Carb Correction\"}]";
        List<TreatmentEvent> a = XdripJson.parseTreatments(json);
        List<TreatmentEvent> b = XdripJson.parseTreatments(json);
        assertEquals(1, a.size());
        assertFalse(a.get(0).id.isEmpty());
        assertEquals(a.get(0).id, b.get(0).id); // стабильный ID при каждом опросе
    }

    @Test
    public void treatmentWithoutValidTimeRejected() {
        // отсутствующее время
        assertTrue(XdripJson.parseTreatments(
                "[{\"_id\":\"a\",\"carbs\":10}]").isEmpty());
        // мусорная строка вместо времени
        assertTrue(XdripJson.parseTreatments(
                "[{\"_id\":\"a\",\"created_at\":\"garbage\",\"carbs\":10}]").isEmpty());
        // нулевое время
        assertTrue(XdripJson.parseTreatments(
                "[{\"_id\":\"a\",\"created_at\":0,\"carbs\":10}]").isEmpty());
        // отрицательное время
        assertTrue(XdripJson.parseTreatments(
                "[{\"_id\":\"a\",\"created_at\":-5000,\"carbs\":10}]").isEmpty());
    }

    @Test
    public void treatmentsInvalidJsonIsNotEmptySnapshot() {
        // невалидный JSON — ошибка загрузки (null), а не достоверно пустой снимок
        assertNull(XdripJson.parseTreatments("{not json"));
        assertNull(XdripJson.parseTreatments("{\"oops\":1}"));
        assertNull(XdripJson.parseTreatments(""));
        assertNull(XdripJson.parseTreatments(null));
        // достоверно пустой массив — валидный пустой снимок
        List<TreatmentEvent> empty = XdripJson.parseTreatments("[]");
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    public void treatmentSecondsTimestampAccepted() {
        List<TreatmentEvent> out = XdripJson.parseTreatments(
                "[{\"_id\":\"s1\",\"created_at\":1715336400,\"insulin\":2}]");
        assertEquals(1, out.size());
        assertEquals(1715336400000L, out.get(0).createdAtMs);
    }

    @Test
    public void timeFieldsNeverReturnNow() {
        assertEquals(-1, TimeFields.toEpochMs(null));
        assertEquals(-1, TimeFields.toEpochMs(""));
        assertEquals(-1, TimeFields.toEpochMs("garbage"));
        assertEquals(-1, TimeFields.toEpochMs(0));
        assertEquals(-1, TimeFields.toEpochMs(-5000));
        assertEquals(1715336100000L, TimeFields.toEpochMs(1715336100000L));
        assertEquals(1715336100000L, TimeFields.toEpochMs(1715336100L)); // секунды
        assertTrue(TimeFields.toEpochMs("2024-05-10T10:15:00.000Z") > 0);
    }
}
