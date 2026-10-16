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
                AlertThresholds.parseMmol("4.1", "12.2"), 42);
    }

    private SgvReading reading(double sgv, long ts, String dir) {
        return new SgvReading(sgv, ts, dir, "id-" + ts);
    }

    @Before
    public void setUp() {
        coordinator = new DeliveryCoordinator();
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
        assertEquals(0, coordinator.drain(sender, 42, now).sent);
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
        coordinator.drain(sender, 42, now);
        assertEquals(1, sender.calls.size());
    }

    @Test
    public void manualSendWhileRunningCreatesMessage() {
        SgvReading r = reading(120, now - 60_000, "Flat");
        coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        coordinator.drain(sender, 42, now); // авто-сообщение доставлено
        assertEquals(1, sender.calls.size());

        // ручная отправка того же доставленного показания — новое логическое сообщение
        assertEquals(DeliveryCoordinator.ReadingOutcome.ACCEPTED,
                coordinator.onReading(r, DeliveryCoordinator.Source.MANUAL, cfg(), msgs, now + 2000));
        coordinator.drain(sender, 42, now + 3000);
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
        coordinator.drain(sender, 42, now);
        coordinator.drain(sender, 42, now);
        assertEquals(2, sender.calls.size());
        assertEquals("reg:" + a.timestampMs + ":Flat", sender.calls.get(0).text);
        assertEquals("reg:" + b.timestampMs + ":SingleUp", sender.calls.get(1).text);
    }

    @Test
    public void expiredBgMessageNotSent() {
        SgvReading r = reading(120, now - 60_000, "Flat");
        coordinator.onReading(r, DeliveryCoordinator.Source.AUTO, cfg(), msgs, now);
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, 42, now);
        // время вышло: сообщение протухло вместе с показанием
        long later = r.timestampMs + ReadingGate.MAX_AGE_MS + 1000;
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, later);
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
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(2, s.sent);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    public void comboRegularFailsAlertSucceeds() {
        enqueueLowBg();
        sender.setRule((peer, text, rid) ->
                peer.equals(PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        assertEquals(1, coordinator.pendingCount());
        // повторяется только обычное: тревожное не дублируется
        s = coordinator.drain(sender, 42, now + 2_000);
        assertEquals(0, s.sent); // notBefore=+5с — ещё рано
        sender.setRule((p, t, rid) -> SendResult.SENT);
        s = coordinator.drain(sender, 42, now + 60_000);
        assertEquals(1, s.sent);
        assertEquals(3, sender.calls.size());
        assertEquals(PEER, sender.calls.get(2).peerId);
    }

    @Test
    public void comboRegularSucceedsAlertFails() {
        enqueueLowBg();
        sender.setRule((peer, text, rid) ->
                peer.equals(ALERT_PEER) ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        sender.setRule((p, t, rid) -> SendResult.SENT);
        s = coordinator.drain(sender, 42, now + 60_000);
        assertEquals(1, s.sent);
        assertEquals(ALERT_PEER, sender.calls.get(2).peerId);
    }

    @Test
    public void comboBothFailIndependentRetry() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(0, s.sent);
        assertEquals(2, s.retried);
        // одно «оживёт» раньше другого — второе продолжает повторяться независимо
        sender.setRule((peer, t, rid) -> peer.equals(PEER) ? SendResult.SENT : SendResult.RETRYABLE);
        s = coordinator.drain(sender, 42, now + 60_000);
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
        coordinator.drain(sender, 42, now);
        int firstRid = sender.calls.get(0).randomId;
        assertNotEquals(0, firstRid);
        sender.setRule((p, t, rid) -> SendResult.SENT);
        coordinator.drain(sender, 42, now + 60_000);
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
        coordinator.drain(sender, 42, now);
        assertEquals(0, coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now + 9000));
        // и после доставки и перезагрузки — тоже
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save());
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
        coordinator.drain(sender, 42, now);
        assertEquals(4, sender.calls.size());
    }

    @Test
    public void partialTreatmentDeliveryRetriesOnlyMissingPart() {
        initTreatments();
        TreatmentEvent e = new TreatmentEvent("pt-1", now - 60_000, 25, 3.0, "Meal");
        coordinator.onTreatments(Arrays.asList(e), cfg(), msgs, true, now);
        // инсулин падает, углеводы уходят
        sender.setRule((p, text, rid) -> text.startsWith("ins:") ? SendResult.RETRYABLE : SendResult.SENT);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(1, s.sent);
        assertEquals(1, s.retried);
        sender.calls.clear();
        sender.setRule((p, t, rid) -> SendResult.SENT);
        coordinator.drain(sender, 42, now + 60_000);
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
        coordinator.drain(sender, 42, now);
        assertTrue(sender.calls.isEmpty());

        // 4. перезапуск: инициализация и «просмотренные» id переживают рестарт
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save());
        assertEquals(0, restored.onTreatments(history, cfg(), msgs, true, now + 5000));

        // 5. новое событие следующего опроса — доставляется ровно один раз
        TreatmentEvent fresh = new TreatmentEvent("new-1", now - 60_000, 0, 2.0, "Bolus");
        assertEquals(1, restored.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now + 10_000));
        restored.drain(sender, 42, now + 10_000);
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
        coordinator.drain(sender, 42, now);
        assertTrue(sender.calls.isEmpty());

        // после рестарта новое событие — один раз
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save());
        TreatmentEvent fresh = new TreatmentEvent("n2", now - 30_000, 0, 1.0, "Bolus");
        assertEquals(1, restored.onTreatments(
                Arrays.asList(fresh), cfg(), msgs, true, now + 5000));
        restored.drain(sender, 42, now + 5000);
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
        coordinator.drain(sender, 42, now);
        assertEquals(1, sender.calls.size());
    }

    // ---------- перезапуск ----------

    @Test
    public void pendingSurvivesRestart() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        coordinator.drain(sender, 42, now);
        int rid = sender.calls.get(0).randomId;

        // «перезапуск процесса»: состояние восстановлено из сериализованной строки
        DeliveryCoordinator restored = DeliveryCoordinator.load(coordinator.save());
        assertEquals(2, restored.pendingCount());
        sender.setRule((p, t, rid2) -> SendResult.SENT);
        DeliveryCoordinator.Summary s = restored.drain(sender, 42, now + 60_000);
        assertEquals(2, s.sent);
        assertEquals(rid, sender.calls.get(2).randomId); // прежний random_id
    }

    // ---------- auth ----------

    @Test
    public void authFailureStopsRetriesUntilTokenChanges() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.AUTH_FAILED);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertTrue(coordinator.isAuthFailed());
        assertEquals(1, s.authFailures); // после первой auth-ошибки обход останавливается

        // с тем же токеном повторять бессмысленно
        sender.calls.clear();
        coordinator.drain(sender, 42, now + 60_000);
        assertTrue(sender.calls.isEmpty());

        // смена токена снимает блокировку: недоставленное (alert) уходит,
        // терминальное (regular, помеченное auth) не воскресает
        sender.setRule((p, t, rid) -> SendResult.SENT);
        DeliveryCoordinator.Summary s2 = coordinator.drain(sender, 43, now + 120_000);
        assertEquals(1, s2.sent);
        assertEquals(ALERT_PEER, sender.calls.get(0).peerId);
    }

    @Test
    public void networkErrorIsNotMaskedAsSuccess() {
        enqueueLowBg();
        sender.setRule((p, t, rid) -> SendResult.RETRYABLE);
        DeliveryCoordinator.Summary s = coordinator.drain(sender, 42, now);
        assertEquals(0, s.sent);
        assertTrue(s.hasPendingFailures());
        assertEquals(2, coordinator.pendingCount());
    }
}
