package com.example.xdrip2vk.core;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DeliveryCoordinatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String PEER = "100";
    private static final String ALERT_PEER = "200";

    /** Подтверждённый контекст «основного» токена/аккаунта. */
    private static final AuthContext AUTH = AuthContext.verified("fp-main", "user:1");
    /** Новая версия токена того же подтверждённого отправителя. */
    private static final AuthContext AUTH2 = AuthContext.verified("fp-other", "user:1");
    /** Подтверждённый контекст ДРУГОГО аккаунта. */
    private static final AuthContext FOREIGN = AuthContext.verified("fp-x", "user:2");

    private DeliveryCoordinator coordinator;
    private long now;

    private static class Call {
        final String peerId;
        final String text;
        final int randomId;

        Call(String peerId, String text, int randomId) {
            this.peerId = peerId;
            this.text = text;
            this.randomId = randomId;
        }
    }

    /** Скриптованный отправитель: по умолчанию всё успешно, сбои задаются правилом. */
    private static class FakeSender implements DeliveryCoordinator.Sender {
        final List<Call> calls = new ArrayList<>();
        Rule rule = (peer, text, rid) -> SendResult.SENT;

        interface Rule {
            SendResult decide(String peer, String text, int randomId);
        }

        void setRule(Rule r) {
            rule = r;
        }

        @Override
        public SendResult send(String peerId, String text, int randomId) {
            calls.add(new Call(peerId, text, randomId));
            return rule.decide(peerId, text, randomId);
        }
    }

    private FakeSender sender;

    private final DeliveryCoordinator.Messages msgs = new DeliveryCoordinator.Messages() {
        @Override
        public String regular(SgvReading r) {
            return "reg:" + r.timestampMs + ":" + r.direction;
        }

        @Override
        public String alert(SgvReading r, AlertThresholds.Level level) {
            return "alert:" + level + ":" + r.timestampMs;
        }

        @Override
        public String treatment(TreatmentEvent e, boolean insulinPart) {
            return (insulinPart ? "ins:" : "carb:") + e.id;
        }
    };

    private DeliveryCoordinator.Config cfg() {
        return cfg(true, true);
    }

    private DeliveryCoordinator.Config cfg(boolean alertsEnabled, boolean configured) {
        return new DeliveryCoordinator.Config(
                PEER, ALERT_PEER, alertsEnabled, configured,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
    }

    private SgvReading reading(double sgv, long ts, String dir) {
        return new SgvReading(sgv, ts, dir, "id-" + ts);
    }

    @Before
    public void setUp() {
        coordinator = new DeliveryCoordinator(() -> now);
        now = NOW;
        sender = new FakeSender();
    }

    // ---------- показания ----------

    @Test
    public void staleReadingStaysStaleAcrossPolls() {
        long staleTs = now - 20 * 60 * 1000;
        for (int i = 0; i < 3; i++) {
            DeliveryCoordinator.ReadingOutcome o = coordinator.onReading(
                    reading(120, staleTs, "Flat"), DeliveryCoordinator.Source.AUTO, cfg(), msgs, now + i * 1000);
            assertEquals(DeliveryCoordinator.ReadingOutcome.STALE, o);
        }
        assertEquals(0, coordinator.pendingCount());
        assertEquals(0, coordinator.drain(sender, AUTH).sent);
        assertTrue(sender.calls.isEmpty());
    }

    @Test
    public void futureReadingRejected() {
        DeliveryCoordinator.ReadingOutcome o = coordinator.onReading(
                reading(120, now + 10 * 60 * 1000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        assertEquals(DeliveryCoordinator.ReadingOutcome.FUTURE, o);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void sameReadingViaBothChannelsNotDuplicated() {
        SgvReading r = reading(120, now - 60_000, "Flat");
        assertEquals(DeliveryCoordinator.ReadingOutcome.ACCEPTED,
                coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now));
        // то же показание через HTTP — дубликат, второе сообщение не создаётся
        assertEquals(DeliveryCoordinator.ReadingOutcome.DUPLICATE,
                coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now + 1000));
        assertEquals(1, coordinator.pendingCount());
        coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
    }

    @Test
    public void manualSendWhileRunningCreatesMessage() {
        SgvReading r = reading(120, now - 60_000, "Flat");
        coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        coordinator.drain(sender, AUTH); // авто-сообщение доставлено
        assertEquals(1, sender.calls.size());

        // ручная отправка того же доставленного показания — новое логическое сообщение
        assertEquals(DeliveryCoordinator.ReadingOutcome.ACCEPTED,
                coordinator.onReading(r, DeliveryCoordinator.Source.MANUAL, cfg(), msgs, now + 2000));
        now += 3000;
        coordinator.drain(sender, AUTH);
        assertEquals(2, sender.calls.size());
        assertTrue(sender.calls.get(1).text.startsWith("reg:"));
    }

    @Test
    public void newReadingDuringSendDoesNotMix() {
        SgvReading a = reading(120, now - 60_000, "Flat");
        coordinator.onReading(a, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);

        // имитируем новое событие в середине сетевого обхода
        SgvReading b = reading(130, now - 50_000, "SingleUp");
        sender.setRule((peer, text, rid) -> {
            coordinator.onReading(b, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
            return SendResult.SENT;
        });
        coordinator.drain(sender, AUTH);
        coordinator.drain(sender, AUTH);
        assertEquals(2, sender.calls.size());
        assertEquals("reg:" + a.timestampMs + ":Flat", sender.calls.get(0).text);
        assertEquals("reg:" + b.timestampMs + ":SingleUp", sender.calls.get(1).text);
    }

    @Test
    public void expiredBgMessageNotSent() {
        SgvReading r = reading(120, now - 60_000, "Flat");
        coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, AUTH);
        // время вышло: сообщение протухло вместе с показанием
        now = r.timestampMs + ReadingGate.MAX_AGE_MS + 1000;
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(0, s.sent);
        assertEquals(1, sender.calls.size()); // повторов не было
        assertEquals(0, coordinator.pendingCount());
    }

    // ---------- сочетания обычное/тревожное ----------

    private void enqueueLowBg() {
        SgvReading low = reading(54, now - 60_000, "SingleDown"); // LOW при порогах 4.1–12.2
        assertEquals(DeliveryCoordinator.ReadingOutcome.ACCEPTED,
                coordinator.onReading(low, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now));
        assertEquals(2, coordinator.pendingCount()); // обычное + тревожное
    }

    @Test
    public void comboBothSuccess() {
        enqueueLowBg();
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(2, s.sent);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void comboRegularFailsAlertSucceeds() {
        enqueueLowBg();
        sender.setRule((peer, text, rid) ->
                peer.equals(PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        assertEquals(1, coordinator.pendingCount());
        // повторяется только обычное: тревожное не дублируется
        now += 2_000;
        s = coordinator.drain(sender, AUTH);
        assertEquals(0, s.sent); // notBefore=+5с — ещё рано
        sender.setRule((p, t, rid) -> SendResult.SENT);
        now += 60_000;
        s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(3, sender.calls.size());
        assertEquals(PEER, sender.calls.get(2).peerId);
    }

    @Test
    public void comboRegularSucceedsAlertFails() {
        enqueueLowBg();
        sender.setRule((peer, text, rid) ->
                peer.equals(ALERT_PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        sender.setRule((p, t, rid) -> SendResult.SENT);
        now += 60_000;
        s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(ALERT_PEER, sender.calls.get(2).peerId);
    }

    @Test
    public void comboBothFailIndependentRetry() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(0, s.sent);
        assertEquals(2, s.retried);
        // одно «оживёт» раньше другого — второе продолжает повторяться независимо
        sender.setRule((peer, t, rid) -> peer.equals(PEER) ? SendResult.SENT : SendResult.RETRYABLE);
        now += 60_000;
        s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        assertEquals(1, coordinator.pendingCount());
    }

    // ---------- random_id и потерянный ответ ----------

    @Test
    public void retryAfterLostResponseUsesSameRandomId() {
        enqueueLowBg();
        // сервер принял сообщение, но ответ потерян → клиент считает отправку неудачной
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, AUTH);
        int firstRid = sender.calls.get(0).randomId;
        assertNotEquals(0, firstRid);
        sender.setRule((p, t, rid) -> SendResult.SENT);
        now += 60_000;
        coordinator.drain(sender, AUTH);
        assertEquals(firstRid, sender.calls.get(2).randomId); // тот же логический random_id
    }

    // ---------- терапия ----------

    private void initTreatments() {
        coordinator.onTreatments(Collections.emptyList(), cfg(), msgs, true, now);
    }

    @Test
    public void firstRunSendsNoBacklog() {
        List<TreatmentEvent> events = XdripJson.parseTreatments(TestUtil.fixture("treatments_xdrip.json"));
        int added = coordinator.onTreatments(events, cfg(), msgs, true, now);
        assertEquals(0, added);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void oneTreatmentProcessedOnce() {
        initTreatments();
        TreatmentEvent e = new TreatmentEvent("t-1", now - 60_000, 30, 2.0, "Meal Bolus");
        assertEquals(2, coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now));
        // та же запись при следующем опросе — не новое событие
        assertEquals(0, coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now + 5000));
        assertEquals(2, coordinator.pendingCount());
        coordinator.drain(sender, AUTH);
        assertEquals(0, coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now + 9000));
        // и после доставки и перезагрузки — тоже
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(0, restored.onTreatments(Arrays.asList(e), cfg(), msgs, true, now + 15000));
    }

    @Test
    public void multipleEventsInWindowNotLost() {
        initTreatments();
        TreatmentEvent e1 = new TreatmentEvent("e1", now - 100_000, 0, 2.0, "Bolus");
        TreatmentEvent e2 = new TreatmentEvent("e2", now - 100_000, 30, 0, "Carbs"); // то же время
        TreatmentEvent e3 = new TreatmentEvent("e3", now - 90_000, 20, 1.0, "Meal");
        int added = coordinator.onTreatments(Arrays.asList(e1, e2, e3), cfg(), msgs, true, now);
        assertEquals(4, added); // ins + carb + ins+carb
        coordinator.drain(sender, AUTH);
        assertEquals(4, sender.calls.size());
    }

    @Test
    public void partialTreatmentDeliveryRetriesOnlyMissingPart() {
        initTreatments();
        TreatmentEvent e = new TreatmentEvent("pt-1", now - 60_000, 25, 3.0, "Meal");
        coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now);
        // инсулин падает, углеводы уходят
        sender.setRule((p, text, rid) -> text.startsWith("ins:") ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        sender.calls.clear();
        sender.setRule((p, t, rid) -> SendResult.SENT);
        now += 60_000;
        coordinator.drain(sender, AUTH);
        // повторилась только инсулиновая часть — доставленная часть не шлётся заново
        assertEquals(1, sender.calls.size());
        assertTrue(sender.calls.get(0).text.startsWith("ins:"));
    }

    @Test
    public void ancientTreatmentNotSent() {
        initTreatments();
        TreatmentEvent old = new TreatmentEvent("old-1", now - DeliveryCoordinator.TREATMENT_MAX_AGE_MS - 1000,
                10, 0, "Carbs");
        assertEquals(0, coordinator.onTreatments(Arrays.asList(old), cfg(), msgs, true, now));
        // и при следующих опросах тоже
        assertEquals(0, coordinator.onTreatments(Arrays.asList(old), cfg(), msgs, true, now + 5000));
        assertEquals(0, coordinator.pendingCount());
    }

    // ---------- время терапии (R01/R02) ----------

    @Test
    public void treatmentWithInvalidTimeNotEnqueued() {
        initTreatments();
        List<TreatmentEvent> bad = Arrays.asList(
                new TreatmentEvent("zero", 0, 10, 0, "Carbs"),
                new TreatmentEvent("neg", -1000, 10, 0, "Carbs"));
        assertEquals(0, coordinator.onTreatments(bad, cfg(), msgs, true, now));
        assertEquals(0, coordinator.pendingCount());
        // id помечены просмотренными — повторные опросы тоже ничего не ставят
        assertEquals(0, coordinator.onTreatments(bad, cfg(), msgs, true, now + 5000));
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void futureTreatmentNotEnqueued() {
        initTreatments();
        TreatmentEvent future = new TreatmentEvent("f1",
                now + 24L * 3600 * 1000, 10, 0, "Carbs");
        assertEquals(0, coordinator.onTreatments(
                Arrays.asList(future), cfg(), msgs, true, now));
        assertEquals(0, coordinator.pendingCount());
        // в пределах допуска рассинхрона — принимается
        TreatmentEvent skew = new TreatmentEvent("f2",
                now + 60_000, 10, 0, "Carbs");
        assertEquals(1, coordinator.onTreatments(
                Arrays.asList(skew), cfg(), msgs, true, now));
    }

    // ---------- ошибка загрузки vs пустой снимок (R03) ----------

    @Test
    public void fetchFailureThenBacklogThenNewEventSequence() {
        // Обязательная последовательность:
        // timeout → некорректный JSON → успешный снимок со старыми событиями
        // → перезапуск → снимок с новым событием.
        // 1. timeout: снимок не получен — инициализации нет
        assertEquals(0, coordinator.onTreatments(null, cfg(), msgs, true, now));

        // 2. некорректный JSON — тоже не валидный снимок
        assertNull(XdripJson.parseTreatments("{broken"));
        assertEquals(0, coordinator.onTreatments(
                XdripJson.parseTreatments("{broken"), cfg(), msgs, true, now));

        // 3. первый успешный снимок со старыми событиями — пометка без отправки
        List<TreatmentEvent> history = XdripJson.parseTreatments(
                TestUtil.fixture("treatments_xdrip.json"));
        assertNotNull(history);
        assertEquals(0, coordinator.onTreatments(history, cfg(), msgs, true, now));
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());

        // 4. перезапуск: инициализация и «просмотренные» id переживают рестарт
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(0, restored.onTreatments(history, cfg(), msgs, true, now + 5000));

        // 5. новое событие следующего опроса — доставляется ровно один раз
        TreatmentEvent fresh = new TreatmentEvent("new-1", now - 60_000, 0, 2.0, "Bolus");
        assertEquals(1, restored.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now + 10_000));
        now += 10_000;
        restored.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
        assertTrue(sender.calls.get(0).text.startsWith("ins:"));
        assertEquals(0, restored.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now + 15_000));
    }

    @Test
    public void malformedArrayDoesNotInitBaseline() {
        // [17, null] — повреждённый ответ: baseline не завершается
        assertNull(XdripJson.parseTreatments("[17, null]"));
        assertEquals(0, coordinator.onTreatments(
                XdripJson.parseTreatments("[17, null]"), cfg(), msgs, true, now));

        // первый корректный снимок со старыми событиями — без бэклога
        List<TreatmentEvent> history = XdripJson.parseTreatments(
                TestUtil.fixture("treatments_xdrip.json"));
        assertEquals(0, coordinator.onTreatments(history, cfg(), msgs, true, now));
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());

        // после рестарта новое событие — один раз
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save(), () -> now);
        TreatmentEvent fresh = new TreatmentEvent("n2", now - 30_000, 0, 1.0, "Bolus");
        assertEquals(1, restored.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now + 5000));
        now += 5000;
        restored.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
    }

    // ---------- значение показания (№2А.1) ----------

    @Test
    public void nonFiniteOrNonPositiveSgvNotAccepted() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0, -3}) {
            assertEquals(DeliveryCoordinator.ReadingOutcome.INVALID,
                    coordinator.onReading(reading(bad, now - 60_000, "Flat"),
                            DeliveryCoordinator.Source.AUTO, cfg(), msgs, now));
        }
        // отклонённые значения не трогают watermark и очередь
        assertEquals(0, coordinator.getLastSeenBgMs());
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void emptySnapshotIsValidBaseline() {
        assertEquals(0, coordinator.onTreatments(
                Collections.emptyList(), cfg(), msgs, true, now));
        TreatmentEvent fresh = new TreatmentEvent("n1", now - 30_000, 15, 0, "Carbs");
        assertEquals(1, coordinator.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now));
        coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
    }

    // ---------- перезапуск ----------

    @Test
    public void pendingSurvivesRestart() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, AUTH);
        int rid = sender.calls.get(0).randomId;

        // «перезапуск процесса»: состояние восстановлено из сериализованной строки
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(2, restored.pendingCount());
        sender.setRule((p, t, rid2) -> SendResult.SENT);
        now += 60_000;
        DeliveryCoordinator.Summary s = restored.drain(sender, AUTH);
        assertEquals(2, s.sent);
        assertEquals(rid, sender.calls.get(2).randomId); // прежний random_id
    }

    // ---------- auth ----------

    @Test
    public void authFailureStopsRetriesUntilTokenChanges() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.AUTH_FAILED);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertTrue(coordinator.isAuthBlocked(AUTH.tokenFp));
        assertEquals(1, s.authFailures); // после первой auth-ошибки обход останавливается

        // с тем же токеном повторять бессмысленно — сетевых попыток нет
        sender.calls.clear();
        now += 60_000;
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());

        // №2Б.2: AUTH_FAILED — возобновляемая блокировка, а не терминальная
        // ошибка. Новый токен того же подтверждённого отправителя возобновляет
        // ОБА сообщения с прежними random_id.
        int ridBefore0 = -1, ridBefore1 = -1;
        for (PendingMessage m : coordinator.pendingMessages()) {
            if (m.peerId.equals(PEER)) ridBefore0 = m.randomId;
            if (m.peerId.equals(ALERT_PEER)) ridBefore1 = m.randomId;
        }
        sender.setRule((p, t, rid) -> SendResult.SENT);
        now += 120_000;
        DeliveryCoordinator.Summary s2 = coordinator.drain(sender, AUTH2);
        assertEquals(2, s2.sent);
        assertEquals(ridBefore0, sender.calls.get(0).randomId);
        assertEquals(ridBefore1, sender.calls.get(1).randomId);
    }

    @Test
    public void foreignSenderDoesNotReceiveOldQueue() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.AUTH_FAILED);
        coordinator.drain(sender, AUTH);

        // Новый токен подтверждён для другого аккаунта: старая очередь
        // ему не передаётся, но и не удаляется — до истечения срока.
        sender.calls.clear();
        sender.setRule((p, t, rid) -> SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, FOREIGN);
        assertTrue(sender.calls.isEmpty());
        assertEquals(2, coordinator.pendingCount());
        assertEquals(2, coordinator.blockedCount(FOREIGN));
        // и под прежним заблокированным токеном они тоже недоступны
        assertEquals(2, coordinator.blockedCount(AUTH));
        // следующее пробуждение — очистка по expiry, не посекундный цикл
        assertTrue(coordinator.nextDueAt(FOREIGN) > now + 1_000);
    }

    @Test
    public void unverifiedTokenKeepsQueueBlocked() {
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        // личность нового токена не подтверждена — отправок нет, очередь цела
        DeliveryCoordinator.Summary s = coordinator.drain(
                sender, AuthContext.unverified("fp-new"));
        assertTrue(sender.calls.isEmpty());
        assertEquals(1, coordinator.pendingCount());
        // после подтверждения того же отправителя — доставка возобновляется
        s = coordinator.drain(sender, AUTH2);
        assertEquals(1, s.sent);
    }

    @Test
    public void unboundEnqueueBindsAfterVerification() {
        // Сообщение создано, пока личность не подтверждена: после проверки
        // той же версии токена оно получает владельца и отправляется.
        DeliveryCoordinator.Config c = new DeliveryCoordinator.Config(
                PEER, ALERT_PEER, true, true,
                AlertThresholds.parseMmol("4.1", "12.2"),
                AuthContext.unverified("fp-pending"));
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, c, msgs, now);
        coordinator.drain(sender, AuthContext.unverified("fp-pending"));
        assertTrue(sender.calls.isEmpty());
        DeliveryCoordinator.Summary s = coordinator.drain(
                sender, AuthContext.verified("fp-pending", "user:1"));
        assertEquals(1, s.sent);
    }

    @Test
    public void terminalExpiredAndRetryLimitNotResumedAfterTokenChange() {
        // терминальная ошибка API для обычного; тревога уходит по expiry
        enqueueLowBg();
        sender.setRule((p, t, r) ->
                p.equals(PEER) ? SendResult.PERMANENT : SendResult.RETRYABLE);
        coordinator.drain(sender, AUTH);
        assertEquals(1, coordinator.terminalCount()); // permanent — терминален
        now += ReadingGate.MAX_AGE_MS;
        coordinator.drain(sender, AUTH); // тревога удалена как просроченная
        assertEquals(0, coordinator.pendingCount());
        // терминальное сообщение тоже вычищается по истечении срока
        assertEquals(0, coordinator.terminalCount());

        // терапия исчерпывает лимит повторов в пределах 6-часового окна
        initTreatments();
        TreatmentEvent e = new TreatmentEvent("t-x", now - 60_000, 0, 2.0, "B");
        coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now);
        sender.setRule((p, t, r) -> SendResult.RETRYABLE);
        for (int i = 0; i < Outbox.MAX_ATTEMPTS; i++) {
            now += 300_000; // 5 мин — достаточно для любого backoff
            coordinator.drain(sender, AUTH);
        }
        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, coordinator.terminalCount()); // retry-limit терапии

        // новый токен того же отправителя терминальные не воскресает
        sender.calls.clear();
        sender.setRule((p, t, r) -> SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH2);
        assertTrue(sender.calls.isEmpty());
        assertEquals(0, s.sent);
    }

    @Test
    public void equalHashCodeTokensAreDistinctFingerprints() {
        // "Aa" и "BB" имеют одинаковый String.hashCode — блокировка одного
        // не должна распространяться на другой.
        assertEquals("Aa".hashCode(), "BB".hashCode());
        String fpA = Fingerprint.of("Aa");
        String fpB = Fingerprint.of("BB");
        assertNotEquals(fpA, fpB);
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, r) -> SendResult.AUTH_FAILED);
        coordinator.drain(sender, AuthContext.verified(fpA, "user:1"));
        assertTrue(coordinator.isAuthBlocked(fpA));
        assertFalse(coordinator.isAuthBlocked(fpB));
        // токен BB того же отправителя возобновляет очередь
        sender.setRule((p, t, r) -> SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(
                sender, AuthContext.verified(fpB, "user:1"));
        assertEquals(1, s.sent);
    }

    @Test
    public void legacyQueueWithoutOwnerNotAttributedToNewToken() {
        // Очередь старой схемы: у сообщений нет владельца и fingerprint —
        // новому подтверждённому аккаунту она не приписывается и не удаляется.
        String legacy = "{\"v\":1,\"outbox\":{\"messages\":[{"
                + "\"key\":\"bg:1\",\"peer\":\"100\",\"text\":\"legacy\","
                + "\"rid\":777,\"exp\":" + (now + 60_000) + ",\"att\":0,\"nb\":0,"
                + "\"term\":false,\"err\":\"\"}]}}";
        coordinator = DeliveryCoordinator.load(legacy, () -> now);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());
        assertEquals(1, coordinator.pendingCount());
        assertEquals(1, coordinator.blockedCount(AUTH)); // владелец неизвестен
        // сохраняется при save/load
        coordinator = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(1, coordinator.blockedCount(AUTH));
    }

    @Test
    public void saveLoadDuringAuthBlockThenRecovery() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.AUTH_FAILED);
        coordinator.drain(sender, AUTH);
        assertTrue(coordinator.isAuthBlocked(AUTH.tokenFp));

        // рестарт в состоянии блокировки: fingerprint блокировки сохранён
        coordinator = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertTrue(coordinator.isAuthBlocked(AUTH.tokenFp));
        assertEquals(2, coordinator.pendingCount());

        // с тем же токеном — тишина; с новым тем же отправителем — оба уходят
        sender.calls.clear();
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());
        sender.setRule((p, t, rid) -> SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH2);
        assertEquals(2, s.sent);
    }

    @Test
    public void networkErrorIsNotMaskedAsSuccess() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(0, s.sent);
        assertTrue(s.hasPendingFailures());
        assertEquals(2, coordinator.pendingCount());
    }

    // ---------- срок действия перед каждой отправкой (№2Б.1) ----------

    @Test
    public void expiryRecheckedBeforeEverySend() {
        enqueueLowBg(); // обычное + тревога одного показания
        long t0 = now;
        // первая отправка «длится» до истечения срока второго сообщения
        sender.setRule((p, t, rid) -> {
            now = t0 + ReadingGate.MAX_AGE_MS + 1;
            return SendResult.SENT;
        });
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size()); // второй sender не вызван
        assertEquals(1, s.sent);
        assertEquals(1, s.expired);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void retryBackoffFromAttemptCompletionNotSweepStart() {
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        long t0 = now;
        // сетевая попытка занимает 20 секунд
        sender.setRule((p, t, rid) -> {
            now += 20_000;
            return SendResult.RETRYABLE;
        });
        coordinator.drain(sender, AUTH);
        // 5-секундный backoff отсчитывается от завершения попытки: t0+20с+5с
        assertEquals(t0 + 25_000, coordinator.nextDueAt(AUTH));
    }

    @Test
    public void expiryAndRandomIdSurviveSaveLoad() {
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        PendingMessage m = coordinator.pendingMessages().get(0);
        long exp = m.expiresAtMs;
        int rid = m.randomId;
        assertNotEquals(0, rid);
        assertEquals(now - 60_000 + ReadingGate.MAX_AGE_MS, exp);

        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(1, restored.pendingCount());
        PendingMessage r2 = restored.pendingMessages().get(0);
        assertEquals(exp, r2.expiresAtMs);
        assertEquals(rid, r2.randomId);
    }

    // ---------- auth-блок и планирование (№2Б.1) ----------

    @Test
    public void authBlockSchedulesExpiryCleanupNotPerSecondLoop() {
        enqueueLowBg();
        sender.setRule((p, t, rid) ->
                p.equals(PEER) ? SendResult.AUTH_FAILED : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, AUTH);
        assertEquals(1, s.authFailures);
        assertTrue(coordinator.isAuthBlocked(AUTH.tokenFp));
        // №2Б.2: auth-заблокированное сообщение не терминально — оба ждут
        assertEquals(2, coordinator.pendingCount());
        // следующее пробуждение — по сроку истечения, не через секунду
        assertTrue(coordinator.nextDueAt(AUTH) > now + 1_000);

        // повторный обход с тем же токеном — сетевых попыток нет
        sender.calls.clear();
        now += 2_000;
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());

        // новое показание при auth-блоке тоже не запускает посекундный цикл
        coordinator.onReading(reading(60, now - 30_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.calls.clear();
        coordinator.drain(sender, AUTH);
        assertTrue(sender.calls.isEmpty());
        assertTrue(coordinator.nextDueAt(AUTH) > now + 1_000);
    }

    @Test
    public void retryableWithoutAuthKeepsBackoff() {
        coordinator.onReading(reading(120, now - 60_000, "Flat"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, AUTH);
        assertEquals(now + 5_000, coordinator.nextDueAt(AUTH)); // первый backoff 5 с
    }

    // ---------- тревожный адресат (№2Б.1) ----------

    @Test
    public void alertPeerZeroOrEmptyNotEnqueued() {
        // "0" — невыбранный чат: тревоги нет в очереди, обычное доставляется
        DeliveryCoordinator.Config c0 = new DeliveryCoordinator.Config(
                PEER, "0", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, c0, msgs, now);
        assertEquals(1, coordinator.pendingCount());
        for (PendingMessage m : coordinator.pendingMessages()) {
            assertNotEquals("0", m.peerId);
        }
        coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
        assertEquals(PEER, sender.calls.get(0).peerId);

        // пустой тревожный адресат — тот же результат
        DeliveryCoordinator c1 = new DeliveryCoordinator(() -> now);
        DeliveryCoordinator.Config ce = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        c1.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, ce, msgs, now);
        assertEquals(1, c1.pendingCount());

        // корректный тревожный адресат — обычное и тревога учитываются независимо
        DeliveryCoordinator c2 = new DeliveryCoordinator(() -> now);
        c2.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        assertEquals(2, c2.pendingCount());
    }

    // ---------- пропущенная тревога без выбранного чата (№2Б.1.1) ----------

    @Test
    public void missedAlertRecordedSurvivesSaveLoadAndExpiresUndelivered() {
        DeliveryCoordinator.Config noAlert = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, noAlert, msgs, now);
        // псевдосообщения нет — только обычное; пропуск зафиксирован со сроком
        long until = coordinator.missedAlertUntil();
        assertEquals(now - 60_000 + ReadingGate.MAX_AGE_MS, until);
        assertEquals(1, coordinator.pendingCount());

        // причина и срок переживают save/load
        coordinator = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(until, coordinator.missedAlertUntil());

        // обычное доставлено; запись о пропущенной тревоге не скрыта
        coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size());
        assertEquals(PEER, sender.calls.get(0).peerId);
        assertTrue(coordinator.missedAlertUntil() > 0);

        // по управляемым часам срок истёк: тревога не отправлена и не доставлена
        now = until + 1;
        coordinator.drain(sender, AUTH);
        assertEquals(1, sender.calls.size()); // новых отправок нет
        assertTrue(coordinator.missedAlertUntil() > 0);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void deliveredAlertClearsMissedRecord() {
        DeliveryCoordinator.Config noAlert = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, noAlert, msgs, now);
        assertTrue(coordinator.missedAlertUntil() > 0);
        coordinator.drain(sender, AUTH);

        // позднее корректно доставленная тревога закрывает старый пропуск —
        // но сама по себе смена чата её не отправляет (отправляет новое событие)
        now += 120_000;
        coordinator.onReading(reading(50, now - 30_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        coordinator.drain(sender, AUTH);
        assertEquals(0, coordinator.missedAlertUntil());
    }

    @Test
    public void staleAlertRetryDoesNotClearNewerMissedAlert() {
        // A: чат тревог выбран, тревога A поставлена, но получает RETRYABLE
        coordinator.onReading(reading(54, now - 120_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, r) ->
                p.equals(ALERT_PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        coordinator.drain(sender, AUTH);
        assertEquals(2, sender.calls.size());
        assertEquals(1, coordinator.pendingCount()); // тревога A ждёт повтора

        // чат убрали; более новое низкое B → тревога B пропущена
        now += 30_000;
        DeliveryCoordinator.Config noAlert = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(50, now - 30_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, noAlert, msgs, now);
        long missedUntil = coordinator.missedAlertUntil();
        assertTrue(missedUntil > 0);

        // успешный повтор старой тревоги A пропуск B не закрывает
        sender.setRule((p, t, r) -> SendResult.SENT);
        coordinator.drain(sender, AUTH);
        assertEquals(missedUntil, coordinator.missedAlertUntil());
    }

    @Test
    public void staleAlertRetryAfterSaveLoadDoesNotClearMissedAlert() {
        coordinator.onReading(reading(54, now - 120_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, r) ->
                p.equals(ALERT_PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        coordinator.drain(sender, AUTH);
        now += 30_000;
        DeliveryCoordinator.Config noAlert = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(50, now - 30_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, noAlert, msgs, now);
        long missedUntil = coordinator.missedAlertUntil();
        assertTrue(missedUntil > 0);

        // очередь сохраняется и восстанавливается перед повтором A
        coordinator = DeliveryCoordinator.load(coordinator.save(), () -> now);
        assertEquals(missedUntil, coordinator.missedAlertUntil());
        assertEquals(2, coordinator.pendingCount()); // тревога A + обычное B

        sender.setRule((p, t, r) -> SendResult.SENT);
        coordinator.drain(sender, AUTH);
        assertEquals(missedUntil, coordinator.missedAlertUntil());
    }

    @Test
    public void failedNewerAlertDoesNotClearMissedAlert() {
        // пропуск: чат тревог не выбран
        DeliveryCoordinator.Config noAlert = new DeliveryCoordinator.Config(
                PEER, "", true, true,
                AlertThresholds.parseMmol("4.1", "12.2"), AUTH);
        coordinator.onReading(reading(54, now - 60_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, noAlert, msgs, now);
        long until = coordinator.missedAlertUntil();
        assertTrue(until > 0);
        coordinator.drain(sender, AUTH); // обычное доставлено

        // чат снова выбран, но новая тревога неуспешна — пропуск не закрывается
        now += 30_000;
        coordinator.onReading(reading(50, now - 30_000, "SingleDown"),
                DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, r) ->
                p.equals(ALERT_PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        coordinator.drain(sender, AUTH);
        assertEquals(until, coordinator.missedAlertUntil());

        // и при терминальной ошибке тоже
        sender.setRule((p, t, r) ->
                p.equals(ALERT_PEER) ? SendResult.PERMANENT : SendResult.SENT);
        now += 6_000;
        coordinator.drain(sender, AUTH);
        assertEquals(until, coordinator.missedAlertUntil());
    }
}
