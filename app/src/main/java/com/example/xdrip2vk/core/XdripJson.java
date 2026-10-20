package com.example.xdrip2vk.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Парсеры ответов локального веб-сервера xDrip+ (Nightscout-совместимый API).
 *
 * Контракт по исходникам xDrip (webservices/WebServiceSgv, WebServiceTreatments):
 *  - sgv.json: "date" = epoch ms, "sgv" всегда мг/дл, "direction", "_id" = uuid.
 *    Строковое время в "dateString"/"sysTime".
 *  - treatments.json: "_id" = uuid записи, "created_at" = epoch ms (у xDrip — число,
 *    у Nightscout может быть ISO-строка), "carbs", "insulin", "eventType".
 * Для совместимости также принимаются альтернативные ключи времени.
 */
public final class XdripJson {

    private static final String[] SGV_TIME_KEYS = {
            "date", "timestamp", "time", "mills", "dateString", "sysTime", "created_at"
    };
    private static final String[] TREATMENT_TIME_KEYS = {
            "created_at", "timestamp", "date", "mills", "dateString", "sysTime"
    };

    private XdripJson() {}

    public static List<SgvReading> parseSgv(String json) {
        List<SgvReading> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                double sgv = item.optDouble("sgv", 0);
                if (!(sgv > 0) || !Double.isFinite(sgv)) {
                    continue;
                }
                long ts = firstTime(item, SGV_TIME_KEYS);
                if (ts <= 0) {
                    continue; // без валидного времени показание не принимается
                }
                String id = item.optString("_id", "");
                if (id.isEmpty()) {
                    id = item.optString("uuid", "");
                }
                out.add(new SgvReading(
                        sgv, ts,
                        item.optString("direction", ""),
                        id,
                        item.optDouble("carbs", 0),
                        item.optDouble("insulin", 0)));
            }
        } catch (JSONException e) {
            // битый ответ — возвращаем что успели разобрать; логирование на стороне вызова
        }
        return out;
    }

    /**
     * @return список событий, либо null если ответ не удалось разобрать
     *         (невалидный JSON — это НЕ достоверно пустой снимок).
     *         События без корректного времени из результата исключаются.
     */
    public static List<TreatmentEvent> parseTreatments(String json) {
        List<TreatmentEvent> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return null; // пустое тело ответа — не валидный снимок
        }
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (JSONException e) {
            return null; // ответ не является JSON-массивом — ошибка, не «пустой снимок»
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                // Необъектный элемент (число, null, строка...) — ответ не
                // соответствует структуре: это ошибка, а не пустой снимок.
                return null;
            }
            double carbs = item.optDouble("carbs", 0);
            double insulin = item.optDouble("insulin", 0);
            if (!(carbs > 0) && !(insulin > 0)) {
                continue;
            }
            long ts = firstTime(item, TREATMENT_TIME_KEYS);
            if (ts <= 0) {
                continue; // без корректного времени событие отклоняется, now не подставляется
            }
            String id = item.optString("_id", "");
            if (id.isEmpty()) {
                id = item.optString("uuid", "");
            }
            String eventType = item.optString("eventType", "");
            if (id.isEmpty()) {
                // Детерминированный ID: одна и та же запись всегда даёт один ключ
                id = "t|" + ts + "|" + carbs + "|" + insulin + "|" + eventType;
            }
            out.add(new TreatmentEvent(id, ts, carbs, insulin, eventType));
        }
        return out;
    }

    private static long firstTime(JSONObject item, String[] keys) {
        for (String key : keys) {
            if (item.has(key)) {
                long ts = TimeFields.toEpochMs(item.opt(key));
                if (ts > 0) {
                    return ts;
                }
            }
        }
        return -1;
    }
}
