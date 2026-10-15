package com.example.xdrip2vk.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Очередь неподтверждённых сообщений. Сериализуется в JSON и сохраняется
 * между запусками процесса. Каждое логическое сообщение отслеживается
 * независимо: сбой одному адресату не влияет на другого.
 *
 * Политика повторов: 5/10/20/40 сек, затем раз в 5 минут; максимум
 * MAX_ATTEMPTS попыток. Сообщение старше expiresAtMs выбрасывается —
 * старая глюкоза не уходит как актуальная.
 */
public final class Outbox {

    public static final int MAX_ATTEMPTS = 12;
    private static final long[] RETRY_DELAYS_MS = {5000, 10000, 20000, 40000};
    private static final long RETRY_DELAY_TAIL_MS = 5 * 60 * 1000L;

    private final LinkedHashMap<String, PendingMessage> map = new LinkedHashMap<>();

    public boolean enqueueIfAbsent(PendingMessage m) {
        if (m == null || m.key == null || m.key.isEmpty() || map.containsKey(m.key)) {
            return false;
        }
        map.put(m.key, m);
        return true;
    }

    public PendingMessage get(String key) {
        return map.get(key);
    }

    public boolean hasPendingForPrefix(String prefix) {
        for (String k : map.keySet()) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Сообщения, готовые к отправке прямо сейчас. */
    public List<PendingMessage> due(long now) {
        List<PendingMessage> out = new ArrayList<>();
        for (PendingMessage m : map.values()) {
            if (!m.terminal && m.expiresAtMs > now && m.notBeforeMs <= now) {
                out.add(m);
            }
        }
        return out;
    }

    public void onSent(String key) {
        map.remove(key);
    }

    public void onRetry(String key, long now) {
        PendingMessage m = map.get(key);
        if (m == null) {
            return;
        }
        m.attempts++;
        if (m.attempts >= MAX_ATTEMPTS) {
            m.terminal = true;
            m.lastError = "retry limit";
        } else {
            m.notBeforeMs = now + delayFor(m.attempts);
        }
    }

    public void onTerminal(String key, String error) {
        PendingMessage m = map.get(key);
        if (m != null) {
            m.terminal = true;
            m.lastError = error == null ? "" : error;
        }
    }

    /** Удаляет истёкшие сообщения. Возвращает число удалённых. */
    public int removeExpired(long now) {
        int removed = 0;
        Iterator<Map.Entry<String, PendingMessage>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs <= now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Ближайшее время, когда стоит проснуться для повтора, или -1. */
    public long nextDueAt(long now) {
        long best = -1;
        for (PendingMessage m : map.values()) {
            if (m.terminal || m.expiresAtMs <= now) {
                continue;
            }
            long t = Math.max(m.notBeforeMs, now);
            if (best < 0 || t < best) {
                best = t;
            }
        }
        return best;
    }

    public boolean hasDue(long now) {
        return !due(now).isEmpty();
    }

    public int pendingCount(boolean includeTerminal) {
        if (includeTerminal) {
            return map.size();
        }
        int n = 0;
        for (PendingMessage m : map.values()) {
            if (!m.terminal) {
                n++;
            }
        }
        return n;
    }

    public List<PendingMessage> all() {
        return new ArrayList<>(map.values());
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        for (PendingMessage m : map.values()) {
            arr.put(m.toJson());
        }
        o.put("messages", arr);
        return o;
    }

    public static Outbox fromJson(JSONObject o) {
        Outbox box = new Outbox();
        if (o == null) {
            return box;
        }
        JSONArray arr = o.optJSONArray("messages");
        if (arr == null) {
            return box;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            PendingMessage m = PendingMessage.fromJson(item);
            if (m != null) {
                box.map.put(m.key, m);
            }
        }
        return box;
    }

    private static long delayFor(int attempts) {
        if (attempts <= RETRY_DELAYS_MS.length) {
            return RETRY_DELAYS_MS[attempts - 1];
        }
        return RETRY_DELAY_TAIL_MS;
    }
}
