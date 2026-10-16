package com.example.xdrip2vk.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Логическое сообщение, ожидающее доставки в VK.
 * key — стабильный идентификатор логического сообщения;
 * randomId — стабильный VK random_id, назначается один раз и хранится
 * для всех повторов (в т.ч. после перезапуска процесса): при потере ответа
 * повторная отправка с тем же random_id не создаст дубль на сервере.
 */
public final class PendingMessage {
    public final String key;
    public final String peerId;
    public final String text;
    public final int randomId;
    public final long expiresAtMs;
    public int attempts;
    public long notBeforeMs;
    public boolean terminal;
    public String lastError;
    /** Подтверждённый владелец ("user:123"/"group:456"); "" — не привязано/legacy. */
    public String senderId;
    /** Fingerprint токена, под которым создано; "" — наследие старой схемы. */
    public String enqueuedFp;
    /**
     * Владелец аккаунта, которому принадлежали выбранные чаты на момент
     * постановки в очередь; "" — чаты не были привязаны к аккаунту
     * (свежая установка до первого подтверждения) или legacy-запись.
     * Защищает от отправки в чужие чаты при смене владельца токена.
     */
    public String peerOwner;

    public PendingMessage(String key, String peerId, String text, int randomId, long expiresAtMs) {
        this.key = key;
        this.peerId = peerId;
        this.text = text;
        this.randomId = randomId;
        this.expiresAtMs = expiresAtMs;
        this.attempts = 0;
        this.notBeforeMs = 0;
        this.terminal = false;
        this.lastError = "";
        this.senderId = "";
        this.enqueuedFp = "";
        this.peerOwner = "";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("peer", peerId);
        o.put("text", text);
        o.put("rid", randomId);
        o.put("exp", expiresAtMs);
        o.put("att", attempts);
        o.put("nb", notBeforeMs);
        o.put("term", terminal);
        o.put("err", lastError == null ? "" : lastError);
        o.put("snd", senderId == null ? "" : senderId);
        o.put("fp", enqueuedFp == null ? "" : enqueuedFp);
        o.put("po", peerOwner == null ? "" : peerOwner);
        return o;
    }

    public static PendingMessage fromJson(JSONObject o) {
        PendingMessage m = new PendingMessage(
                o.optString("key", ""),
                o.optString("peer", ""),
                o.optString("text", ""),
                o.optInt("rid", 0),
                o.optLong("exp", 0));
        m.attempts = o.optInt("att", 0);
        m.notBeforeMs = o.optLong("nb", 0);
        m.terminal = o.optBoolean("term", false);
        m.lastError = o.optString("err", "");
        // У записей старой схемы владельца нет — такие сообщения остаются
        // заблокированными («владелец неизвестен») до истечения срока.
        m.senderId = o.optString("snd", "");
        m.enqueuedFp = o.optString("fp", "");
        m.peerOwner = o.optString("po", "");
        if (m.key.isEmpty() || m.randomId == 0) {
            return null;
        }
        return m;
    }
}
