package com.example.xdrip2vk.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Координатор доставки: принимает показания и события терапии из всех входных
 * каналов, дедуплицирует их, ставит логические сообщения в персистентный outbox
 * и подтверждает обработку только после успешной отправки.
 *
 * Состояние целиком сериализуется (save()/load()) и переживает перезапуск процесса.
 * Весь доступ к экземпляру должен быть сериализован (в приложении — через
 * единственный HandlerThread сервиса).
 */
public final class DeliveryCoordinator {

    public static final long TREATMENT_MAX_AGE_MS = 6 * 60 * 60 * 1000L; // 6 часов
    private static final int SEEN_IDS_CAP = 500;
    private static final int STATE_VERSION = 1;

    public enum Source {AUTO, MANUAL}

    public enum ReadingOutcome {ACCEPTED, DUPLICATE, STALE, FUTURE, INVALID}

    public interface Sender {
        SendResult send(String peerId, String text, int randomId);
    }

    /** Фабрика текстов сообщений; реализуется на стороне сервиса (нужны настройки/UI-строки). */
    public interface Messages {
        String regular(SgvReading r);

        String alert(SgvReading r, AlertThresholds.Level level);

        String treatment(TreatmentEvent e, boolean insulinPart);
    }

    /** Снимок конфигурации на момент обработки — не читается повторно внутри вызова. */
    public static final class Config {
        public final String normalPeer;
        public final String alertPeer;
        public final boolean alertsEnabled;
        public final boolean configured; // токен+чат заданы — есть смысл ставить в очередь
        public final AlertThresholds thresholds;
        public final int tokenHash;

        public Config(String normalPeer, String alertPeer, boolean alertsEnabled,
                      boolean configured, AlertThresholds thresholds, int tokenHash) {
            this.normalPeer = normalPeer == null ? "" : normalPeer;
            this.alertPeer = alertPeer == null ? "" : alertPeer;
            this.alertsEnabled = alertsEnabled;
            this.configured = configured;
            this.thresholds = thresholds;
            this.tokenHash = tokenHash;
        }
    }

    public static final class Summary {
        public int sent;
        public int retried;
        public int authFailures;
        public int permanent;
        public int expired;

        public boolean hasPendingFailures() {
            return retried > 0 || authFailures > 0 || permanent > 0;
        }
    }

    private final Outbox outbox = new Outbox();
    private final Clock clock;
    private long lastSeenBgMs;
    /** Срок актуальности пропущенной тревоги (чат не был выбран); 0 — нет. */
    private long missedAlertUntilMs;
    private boolean treatmentsInitialized;
    private final LinkedHashSet<String> seenTreatmentIds = new LinkedHashSet<>();
    private boolean authFailed;
    private int authFailedTokenHash;
    private final SecureRandom random = new SecureRandom();

    public DeliveryCoordinator() {
        this(Clock.SYSTEM);
    }

    public DeliveryCoordinator(Clock clock) {
        this.clock = clock;
    }

    /** Адресат VK валиден: непустой и не «0» (невыбранный чат). */
    static boolean isValidPeer(String peer) {
        return peer != null && !peer.isEmpty() && !"0".equals(peer);
    }

    // ---------- SGV ----------

    public ReadingOutcome onReading(SgvReading r, Source src, Config cfg, Messages msgs, long now) {
        // Повторная проверка значения: ни один входной канал (intent,
        // candidate-кеш, HTTP) не должен принести NaN/Infinity/<=0 до watermark
        // и очереди.
        if (!(r.sgvMgdl > 0) || !Double.isFinite(r.sgvMgdl)) {
            return ReadingOutcome.INVALID;
        }
        ReadingGate.Verdict v = ReadingGate.check(r.timestampMs, now);
        if (v == ReadingGate.Verdict.INVALID) {
            return ReadingOutcome.INVALID;
        }
        if (v == ReadingGate.Verdict.FUTURE) {
            return ReadingOutcome.FUTURE;
        }
        if (r.timestampMs <= lastSeenBgMs) {
            // Повтор того же показания через другой канал — не новое событие.
            // Если сообщение ещё в очереди, повтор обработает drain().
            if (src == Source.MANUAL && v == ReadingGate.Verdict.FRESH
                    && cfg.configured && isValidPeer(cfg.normalPeer)
                    && !outbox.hasPendingForPrefix("bg:" + r.timestampMs)) {
                // Ручная отправка доставленного показания — новое логическое сообщение
                enqueue(new PendingMessage(
                        "bg:" + r.timestampMs + ":m:" + now,
                        cfg.normalPeer, msgs.regular(r), newRandomId(),
                        r.timestampMs + ReadingGate.MAX_AGE_MS));
                return ReadingOutcome.ACCEPTED;
            }
            // Для устаревшего повтора возвращаем реальный вердикт — информативнее DUPLICATE
            return v == ReadingGate.Verdict.FRESH ? ReadingOutcome.DUPLICATE : ReadingOutcome.STALE;
        }
        // Фиксируем watermark даже для устаревшего показания: ещё более старые
        // данные при следующих опросах не должны считаться новыми.
        lastSeenBgMs = r.timestampMs;
        if (v == ReadingGate.Verdict.STALE) {
            return ReadingOutcome.STALE;
        }
        if (!cfg.configured || !isValidPeer(cfg.normalPeer)) {
            return ReadingOutcome.ACCEPTED; // запомнили, но отправлять некуда
        }

        long expires = r.timestampMs + ReadingGate.MAX_AGE_MS;
        if (src == Source.MANUAL) {
            // Ручная отправка всегда создаёт новое логическое сообщение,
            // если это показание уже не ждёт в очереди.
            if (!outbox.hasPendingForPrefix("bg:" + r.timestampMs)) {
                enqueue(new PendingMessage(
                        "bg:" + r.timestampMs + ":m:" + now,
                        cfg.normalPeer, msgs.regular(r), newRandomId(), expires));
            }
        } else {
            enqueue(new PendingMessage(
                    "bg:" + r.timestampMs,
                    cfg.normalPeer, msgs.regular(r), newRandomId(), expires));
        }

        if (cfg.alertsEnabled) {
            AlertThresholds.Level level = cfg.thresholds.classify(r.sgvMgdl);
            if (level != AlertThresholds.Level.NORMAL) {
                if (isValidPeer(cfg.alertPeer)) {
                    enqueue(new PendingMessage(
                            "alert:" + r.timestampMs,
                            cfg.alertPeer, msgs.alert(r, level), newRandomId(), expires));
                } else {
                    // Чат тревог не выбран: псевдосообщение для peer_id=0 не
                    // создаём, но фиксируем пропущенную тревогу — она остаётся
                    // актуальной до истечения срока самого показания и не
                    // должна скрываться успехом других сообщений.
                    missedAlertUntilMs = expires;
                }
            }
        }
        return ReadingOutcome.ACCEPTED;
    }

    // ---------- Терапия ----------

    /**
     * Обрабатывает свежую порцию событий терапии.
     * @param events null — снимок не получен (сетевая ошибка или невалидный JSON);
     *               учёт НЕ инициализируется и события не помечаются просмотренными.
     *               Пустой список — достоверно пустой снимок: инициализация выполняется.
     *               Первый валидный снимок отмечает все существующие записи
     *               просмотренными без отправки бэклога.
     * @return число поставленных в очередь частей сообщений.
     */
    public int onTreatments(List<TreatmentEvent> events, Config cfg, Messages msgs,
                            boolean enabled, long now) {
        if (events == null) {
            return 0;
        }
        if (!treatmentsInitialized) {
            treatmentsInitialized = true;
            for (TreatmentEvent e : events) {
                markSeen(e.id);
            }
            return 0;
        }
        List<TreatmentEvent> sorted = new ArrayList<>(events);
        Collections.sort(sorted, new Comparator<TreatmentEvent>() {
            @Override
            public int compare(TreatmentEvent a, TreatmentEvent b) {
                return Long.compare(a.createdAtMs, b.createdAtMs);
            }
        });

        int added = 0;
        boolean canSend = enabled && cfg.configured && isValidPeer(cfg.normalPeer);
        for (TreatmentEvent e : sorted) {
            if (e.id.isEmpty() || seenTreatmentIds.contains(e.id)) {
                continue;
            }
            markSeen(e.id);
            if (!canSend) {
                continue;
            }
            // Повторная проверка времени: прямой вызов с некорректным событием
            // тоже не должен создавать сообщение.
            if (e.createdAtMs <= 0) {
                continue; // время отсутствует/некорректно — now не подставляется
            }
            if (e.createdAtMs > now + ReadingGate.MAX_FUTURE_SKEW_MS) {
                continue; // событие из будущего сверх допуска рассинхрона
            }
            if (now - e.createdAtMs > TREATMENT_MAX_AGE_MS) {
                continue; // старше шестичасового окна терапии
            }
            long expires = e.createdAtMs + TREATMENT_MAX_AGE_MS;
            if (e.hasInsulin()) {
                if (enqueue(new PendingMessage("treat:" + e.id + ":ins",
                        cfg.normalPeer, msgs.treatment(e, true), newRandomId(), expires))) {
                    added++;
                }
            }
            if (e.hasCarbs()) {
                if (enqueue(new PendingMessage("treat:" + e.id + ":carb",
                        cfg.normalPeer, msgs.treatment(e, false), newRandomId(), expires))) {
                    added++;
                }
            }
        }
        return added;
    }

    // ---------- Доставка ----------

    /**
     * Попытка доставить все готовые сообщения. Каждое сообщение независимо:
     * успех одного не подтверждает другие. Ошибка авторизации помечает сообщение
     * терминальным и блокирует повторы до смены токена.
     *
     * Время запрашивается у clock непосредственно перед каждой отправкой:
     * если пока шла предыдущая сетевая попытка срок сообщения истёк,
     * отправка не начинается. Backoff отсчитывается от завершения попытки.
     */
    public Summary drain(Sender sender, int tokenHash) {
        if (authFailed && tokenHash != authFailedTokenHash) {
            authFailed = false; // токен сменился — пробуем снова
        }
        Summary s = new Summary();
        s.expired += outbox.removeExpired(clock.now());

        for (PendingMessage m : new ArrayList<>(outbox.all())) {
            long t = clock.now();
            if (m.terminal || m.notBeforeMs > t) {
                continue;
            }
            if (m.expiresAtMs <= t) {
                // просрочено пока выполнялась предыдущая отправка — не шлём
                outbox.remove(m.key);
                s.expired++;
                continue;
            }
            if (authFailed) {
                continue; // авторизация сломана — повторы бессмысленны
            }
            SendResult res;
            try {
                res = sender.send(m.peerId, m.text, m.randomId);
            } catch (Exception e) {
                res = SendResult.RETRYABLE;
            }
            if (res == null) {
                res = SendResult.RETRYABLE;
            }
            long done = clock.now(); // время завершения сетевой попытки
            switch (res) {
                case SENT:
                    outbox.onSent(m.key);
                    s.sent++;
                    // Пропущенная тревога закрывается только доставкой тревоги
                    // того же или более нового показания. У всех тревог окно
                    // одинаковое (время показания + 15 мин), поэтому
                    // expiresAtMs >= missedAlertUntilMs ⇔ показание не старше.
                    // Успешный повтор старой тревоги запись о новом пропуске
                    // не трогает; RETRYABLE/AUTH_FAILED/PERMANENT — тем более.
                    if (m.key.startsWith("alert:") && missedAlertUntilMs > 0
                            && m.expiresAtMs >= missedAlertUntilMs) {
                        missedAlertUntilMs = 0;
                    }
                    break;
                case RETRYABLE:
                    outbox.onRetry(m.key, done); // backoff от завершения попытки
                    s.retried++;
                    break;
                case AUTH_FAILED:
                    outbox.onTerminal(m.key, "auth");
                    authFailed = true;
                    authFailedTokenHash = tokenHash;
                    s.authFailures++;
                    break;
                case PERMANENT:
                default:
                    outbox.onTerminal(m.key, "api");
                    s.permanent++;
                    break;
            }
        }
        s.expired += outbox.removeExpired(clock.now());
        return s;
    }

    /**
     * Ближайшее время, когда стоит проснуться, или -1.
     * При auth-блокировке сетевых попыток нет — планируется только очистка
     * по ближайшему сроку истечения, а не посекундный drain.
     */
    public long nextDueAt() {
        long now = clock.now();
        if (authFailed) {
            long best = -1;
            for (PendingMessage m : outbox.all()) {
                if (m.terminal || m.expiresAtMs <= now) {
                    continue;
                }
                if (best < 0 || m.expiresAtMs < best) {
                    best = m.expiresAtMs;
                }
            }
            return best;
        }
        return outbox.nextDueAt(now);
    }

    public boolean hasDue() {
        return outbox.hasDue(clock.now());
    }

    public int pendingCount() {
        return outbox.pendingCount(false);
    }

    /** Число терминально недоставленных сообщений, оставшихся в очереди. */
    public int terminalCount() {
        return outbox.pendingCount(true) - outbox.pendingCount(false);
    }

    public List<PendingMessage> pendingMessages() {
        return outbox.all();
    }

    public boolean isAuthFailed() {
        return authFailed;
    }

    /**
     * Срок актуальности пропущенной тревоги (чат не был выбран), или 0.
     * now < значения — тревога ещё актуальна; now >= значения — срок вышел,
     * тревога так и не была отправлена.
     */
    public long missedAlertUntil() {
        return missedAlertUntilMs;
    }

    public long getLastSeenBgMs() {
        return lastSeenBgMs;
    }

    public boolean isTreatmentSeen(String id) {
        return seenTreatmentIds.contains(id);
    }

    // ---------- Персистентность ----------

    public String save() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", STATE_VERSION);
            o.put("lastSeenBgMs", lastSeenBgMs);
            o.put("treatInit", treatmentsInitialized);
            o.put("authFailed", authFailed);
            o.put("authTokenHash", authFailedTokenHash);
            o.put("missedAlertMs", missedAlertUntilMs);
            JSONArray ids = new JSONArray();
            for (String id : seenTreatmentIds) {
                ids.put(id);
            }
            o.put("seenTreat", ids);
            o.put("outbox", outbox.toJson());
            return o.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public static DeliveryCoordinator load(String json) {
        return load(json, Clock.SYSTEM);
    }

    public static DeliveryCoordinator load(String json, Clock clock) {
        DeliveryCoordinator c = new DeliveryCoordinator(clock);
        if (json == null || json.isEmpty()) {
            return c;
        }
        try {
            JSONObject o = new JSONObject(json);
            c.lastSeenBgMs = o.optLong("lastSeenBgMs", 0);
            c.treatmentsInitialized = o.optBoolean("treatInit", false);
            c.authFailed = o.optBoolean("authFailed", false);
            c.authFailedTokenHash = o.optInt("authTokenHash", 0);
            c.missedAlertUntilMs = o.optLong("missedAlertMs", 0);
            JSONArray ids = o.optJSONArray("seenTreat");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    c.markSeen(ids.optString(i, ""));
                }
            }
            Outbox box = Outbox.fromJson(o.optJSONObject("outbox"));
            for (PendingMessage m : box.all()) {
                c.outbox.enqueueIfAbsent(m);
            }
        } catch (JSONException e) {
            return new DeliveryCoordinator();
        }
        return c;
    }

    private boolean enqueue(PendingMessage m) {
        return outbox.enqueueIfAbsent(m);
    }

    private void markSeen(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        seenTreatmentIds.add(id);
        while (seenTreatmentIds.size() > SEEN_IDS_CAP) {
            String oldest = seenTreatmentIds.iterator().next();
            seenTreatmentIds.remove(oldest);
        }
    }

    private int newRandomId() {
        int id = random.nextInt();
        return id == 0 ? 1 : id;
    }
}
