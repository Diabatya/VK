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

    /**
     * Подтверждённая запись текущего снимка состояния. Вызывается только с
     * потока, владеющего координатором (рабочий поток сервиса). Возвращает
     * false, если запись на диск не подтверждена — сетевую попытку после
     * такого отказа начинать нельзя.
     */
    public interface Checkpoint {
        boolean persist();
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
        public final AuthContext auth;
        /** Владелец аккаунта, которому принадлежат выбранные чаты; "" — неизвестен. */
        public final String chatOwner;

        public Config(String normalPeer, String alertPeer, boolean alertsEnabled,
                      boolean configured, AlertThresholds thresholds, AuthContext auth) {
            this(normalPeer, alertPeer, alertsEnabled, configured, thresholds, auth,
                    auth != null && auth.verified ? auth.senderId : "");
        }

        public Config(String normalPeer, String alertPeer, boolean alertsEnabled,
                      boolean configured, AlertThresholds thresholds, AuthContext auth,
                      String chatOwner) {
            this.normalPeer = normalPeer == null ? "" : normalPeer;
            this.alertPeer = alertPeer == null ? "" : alertPeer;
            this.alertsEnabled = alertsEnabled;
            this.configured = configured;
            this.thresholds = thresholds;
            this.auth = auth == null ? AuthContext.NONE : auth;
            this.chatOwner = chatOwner == null ? "" : chatOwner;
        }
    }

    public static final class Summary {
        public int sent;
        public int retried;
        public int authFailures;
        public int permanent;
        public int expired;
        /** Обход прерван: представленный токен сменился в середине серии отправок. */
        public boolean aborted;
        /** Обход остановлен: подтверждённая запись состояния на диск не удалась. */
        public boolean persistFailed;

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
    /** Fingerprint токена, давшего AUTH_FAILED; "" — блокировки нет. */
    private String blockedTokenFp = "";
    private final SecureRandom random = new SecureRandom();
    /** Checkpoint подтверждённой записи; по умолчанию — успешный no-op (тесты). */
    private Checkpoint checkpoint = () -> true;
    /**
     * В памяти есть изменения, не подтверждённые записью на диск. Перед каждой
     * сетевой попыткой такое состояние обязано попасть в checkpoint: при сбое
     * процесса восстановление идёт с последней успешной записи.
     */
    private boolean persistDirty;

    /** Установить механизм checkpoint (вызывается из того же потока, что и drain). */
    public void setCheckpoint(Checkpoint cp) {
        checkpoint = cp == null ? () -> true : cp;
    }

    /** Есть неподтверждённые диском изменения состояния. */
    public boolean isPersistDirty() {
        return persistDirty;
    }

    /**
     * Если состояние «грязное» — выполнить checkpoint. true — на диске
     * актуальный снимок (или грязных изменений нет); false — запись не
     * подтверждена, состояние остаётся в памяти для повторной записи.
     */
    public boolean persistNow() {
        if (!persistDirty) {
            return true;
        }
        if (checkpoint.persist()) {
            persistDirty = false;
            return true;
        }
        return false;
    }

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
                enqueue(bound(new PendingMessage(
                        "bg:" + r.timestampMs + ":m:" + now,
                        cfg.normalPeer, msgs.regular(r), newRandomId(),
                        r.timestampMs + ReadingGate.MAX_AGE_MS), cfg));
                return ReadingOutcome.ACCEPTED;
            }
            // Для устаревшего повтора возвращаем реальный вердикт — информативнее DUPLICATE
            return v == ReadingGate.Verdict.FRESH ? ReadingOutcome.DUPLICATE : ReadingOutcome.STALE;
        }
        // Фиксируем watermark даже для устаревшего показания: ещё более старые
        // данные при следующих опросах не должны считаться новыми.
        lastSeenBgMs = r.timestampMs;
        persistDirty = true;
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
                enqueue(bound(new PendingMessage(
                        "bg:" + r.timestampMs + ":m:" + now,
                        cfg.normalPeer, msgs.regular(r), newRandomId(), expires), cfg));
            }
        } else {
            enqueue(bound(new PendingMessage(
                    "bg:" + r.timestampMs,
                    cfg.normalPeer, msgs.regular(r), newRandomId(), expires), cfg));
        }

        if (cfg.alertsEnabled) {
            AlertThresholds.Level level = cfg.thresholds.classify(r.sgvMgdl);
            if (level != AlertThresholds.Level.NORMAL) {
                if (isValidPeer(cfg.alertPeer)) {
                    enqueue(bound(new PendingMessage(
                            "alert:" + r.timestampMs,
                            cfg.alertPeer, msgs.alert(r, level), newRandomId(), expires), cfg));
                } else {
                    // Чат тревог не выбран: псевдосообщение для peer_id=0 не
                    // создаём, но фиксируем пропущенную тревогу — она остаётся
                    // актуальной до истечения срока самого показания и не
                    // должна скрываться успехом других сообщений.
                    missedAlertUntilMs = expires;
                    persistDirty = true;
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
            persistDirty = true; // baseline терапии — изменение без сетевой отправки
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
                if (enqueue(bound(new PendingMessage("treat:" + e.id + ":ins",
                        cfg.normalPeer, msgs.treatment(e, true), newRandomId(), expires), cfg))) {
                    added++;
                }
            }
            if (e.hasCarbs()) {
                if (enqueue(bound(new PendingMessage("treat:" + e.id + ":carb",
                        cfg.normalPeer, msgs.treatment(e, false), newRandomId(), expires), cfg))) {
                    added++;
                }
            }
        }
        return added;
    }

    // ---------- Доставка ----------

    /**
     * Попытка доставить все готовые сообщения. Каждое сообщение независимо:
     * успех одного не подтверждает другие. Ошибка авторизации НЕ делает
     * сообщение терминальным — оно остаётся auth-заблокированным и
     * возобновляется после подтверждённого обновления токена того же
     * отправителя. Сетевых попыток с заблокированным токеном нет; при
     * неподтверждённой личности очередь сохраняется без отправок; сообщения
     * чужого подтверждённого аккаунта или без владельца не отправляются.
     *
     * Время запрашивается у clock непосредственно перед каждой отправкой:
     * если пока шла предыдущая сетевая попытка срок сообщения истёк,
     * отправка не начинается. Backoff отсчитывается от завершения попытки.
     */
    public Summary drain(Sender sender, AuthContext auth) {
        return drain(sender, auth, () -> true);
    }

    /**
     * @param authCurrent проверяется непосредственно перед каждой сетевой
     *                    попыткой: если представленная версия токена сменилась
     *                    в середине обхода, оставшиеся отправки останавливаются
     *                    (aborted=true). Результат уже завершённой попытки
     *                    учитывается как обычно; неотправленные сообщения
     *                    остаются в очереди без attempts/смены random_id.
     */
    public Summary drain(Sender sender, AuthContext auth,
                         java.util.function.BooleanSupplier authCurrent) {
        Summary s = new Summary();
        int removedStart = outbox.removeExpired(clock.now());
        if (removedStart > 0) {
            s.expired += removedStart;
            persistDirty = true;
        }
        // Подтверждённая запись на диск до любой сети: при сбое процесса
        // восстановление пойдёт с этого снимка. Отказ диска — обход не
        // начинается, очередь в памяти сохраняется.
        if (!persistNow()) {
            s.persistFailed = true;
            return s;
        }
        // Тот же заблокированный токен: сетевых попыток нет вообще.
        boolean tokenBlocked = !auth.tokenFp.isEmpty()
                && auth.tokenFp.equals(blockedTokenFp);
        boolean sweepStopped = false;

        for (PendingMessage m : new ArrayList<>(outbox.all())) {
            long t = clock.now();
            if (m.terminal || m.notBeforeMs > t) {
                continue;
            }
            if (m.expiresAtMs <= t) {
                // просрочено пока выполнялась предыдущая отправка — не шлём
                outbox.remove(m.key);
                s.expired++;
                persistDirty = true;
                continue;
            }
            if (sweepStopped || tokenBlocked || !auth.verified || !ownerMatches(m, auth)) {
                continue; // очередь сохраняется, но сеть не трогаем
            }
            // Checkpoint до сетевой попытки: random_id, адресат, владельцы,
            // expiry и поздняя привязка уже на диске. Отказ записи — эту и
            // следующие попытки не начинаем, attempts не увеличиваем.
            if (!persistNow()) {
                s.persistFailed = true;
                break;
            }
            // Повторные проверки ПОСЛЕ последней блокирующей записи: за время
            // checkpoint мог истечь срок, смениться токен или начаться
            // завершение — тогда send не выполняется.
            t = clock.now();
            if (m.expiresAtMs <= t) {
                outbox.remove(m.key);
                s.expired++;
                persistDirty = true;
                if (!persistNow()) { // удаление просроченного тоже на диск
                    s.persistFailed = true;
                    break;
                }
                continue;
            }
            if (!authCurrent.getAsBoolean()) {
                s.aborted = true; // токен сменился/завершение — следующих отправок нет
                break;
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
                    // Возобновляемая блокировка, а не окончательная ошибка:
                    // random_id, адресат, текст и expiry сохраняются.
                    outbox.onAuthBlocked(m.key);
                    blockedTokenFp = auth.tokenFp;
                    s.authFailures++;
                    sweepStopped = true; // остальным этот токен тоже не пройдёт
                    break;
                case PERMANENT:
                default:
                    outbox.onTerminal(m.key, "api");
                    s.permanent++;
                    break;
            }
            // Результат завершённой попытки — на диск до следующей отправки.
            // Отказ записи: следующие отправки останавливаются, изменённое
            // состояние остаётся в памяти для повторной записи.
            persistDirty = true;
            if (!persistNow()) {
                s.persistFailed = true;
                break;
            }
        }
        int removedEnd = outbox.removeExpired(clock.now());
        if (removedEnd > 0) {
            s.expired += removedEnd;
            persistDirty = true;
        }
        return s;
    }

    /**
     * Можно ли сейчас пытаться отправить сообщение данным auth-контекстом.
     * Непривязанное сообщение связывается с подтверждённым владельцем только
     * если оно создано под той же версией токена (enqueuedFp совпадает) И
     * адресаты были выбраны этим же аккаунтом (peerOwner) — нельзя считать,
     * что чужие чаты принадлежат новому владельцу только по fingerprint.
     * Очередь старой схемы без владельца новому аккаунту не приписывается.
     * Адресат и владелец при этом не переписываются.
     */
    private boolean ownerMatches(PendingMessage m, AuthContext auth) {
        if (m.senderId == null || m.senderId.isEmpty()) {
            if (!m.enqueuedFp.isEmpty() && m.enqueuedFp.equals(auth.tokenFp)
                    && (m.peerOwner.isEmpty() || m.peerOwner.equals(auth.senderId))) {
                m.senderId = auth.senderId; // поздняя привязка после подтверждения
                persistDirty = true; // привязка обязана попасть в checkpoint до send
                return true;
            }
            return false; // владелец неизвестен или чужой
        }
        return m.senderId.equals(auth.senderId)
                && (m.peerOwner.isEmpty() || m.peerOwner.equals(auth.senderId));
    }

    /**
     * Ближайшее время, когда стоит проснуться, или -1.
     * Сообщения, недоступные текущему auth (заблокированный токен,
     * неподтверждённая личность, чужой/неизвестный владелец), планируют
     * только очистку по сроку истечения — не посекундный drain.
     */
    public long nextDueAt(AuthContext auth) {
        long now = clock.now();
        boolean tokenBlocked = !auth.tokenFp.isEmpty()
                && auth.tokenFp.equals(blockedTokenFp);
        long best = -1;
        for (PendingMessage m : outbox.all()) {
            if (m.terminal || m.expiresAtMs <= now) {
                continue;
            }
            boolean eligible = !tokenBlocked && auth.verified && ownerMatchesPeek(m, auth);
            long t = eligible ? Math.max(m.notBeforeMs, now) : m.expiresAtMs;
            if (best < 0 || t < best) {
                best = t;
            }
        }
        return best;
    }

    /** Та же проверка владельца, но без привязки (для планирования/статуса). */
    private boolean ownerMatchesPeek(PendingMessage m, AuthContext auth) {
        if (m.senderId == null || m.senderId.isEmpty()) {
            return !m.enqueuedFp.isEmpty() && m.enqueuedFp.equals(auth.tokenFp)
                    && (m.peerOwner.isEmpty() || m.peerOwner.equals(auth.senderId));
        }
        return m.senderId.equals(auth.senderId)
                && (m.peerOwner.isEmpty() || m.peerOwner.equals(auth.senderId));
    }

    /**
     * Сколько нетерминальных непросроченных сообщений недоступно данному
     * auth-контексту (чужой подтверждённый аккаунт, неизвестный владелец
     * старой схемы, заблокированный токен). Для достоверного статуса.
     */
    public int blockedCount(AuthContext auth) {
        long now = clock.now();
        boolean tokenBlocked = !auth.tokenFp.isEmpty()
                && auth.tokenFp.equals(blockedTokenFp);
        int n = 0;
        for (PendingMessage m : outbox.all()) {
            if (m.terminal || m.expiresAtMs <= now) {
                continue;
            }
            if (tokenBlocked || !auth.verified || !ownerMatchesPeek(m, auth)) {
                n++;
            }
        }
        return n;
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

    /** Данный fingerprint токена сейчас заблокирован ошибкой авторизации. */
    public boolean isAuthBlocked(String tokenFp) {
        return tokenFp != null && !tokenFp.isEmpty() && tokenFp.equals(blockedTokenFp);
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
            o.put("blockedFp", blockedTokenFp);
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
            c.blockedTokenFp = o.optString("blockedFp", "");
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
        c.persistDirty = false; // только что загружено с диска — грязных изменений нет
        return c;
    }

    /**
     * Привязка нового сообщения к контексту авторизации: подтверждённый
     * владелец записывается сразу; без подтверждения сообщение помечается
     * версией токена, под которой создано, — после успешной проверки того
     * же токена оно получит владельца автоматически.
     */
    private PendingMessage bound(PendingMessage m, Config cfg) {
        m.senderId = cfg.auth.verified ? cfg.auth.senderId : "";
        m.enqueuedFp = cfg.auth.tokenFp;
        m.peerOwner = cfg.chatOwner; // владелец чатов на момент постановки
        return m;
    }

    private boolean enqueue(PendingMessage m) {
        boolean added = outbox.enqueueIfAbsent(m);
        if (added) {
            persistDirty = true;
        }
        return added;
    }

    private void markSeen(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        seenTreatmentIds.add(id);
        persistDirty = true;
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
