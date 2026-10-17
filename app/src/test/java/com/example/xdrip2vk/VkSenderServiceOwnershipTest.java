package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.PendingMessage;
import com.example.xdrip2vk.core.SendResult;
import com.example.xdrip2vk.core.TreatmentEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * №2Б.4.1: гонка экземпляров (передача владения состоянием доставки),
 * baseline терапии при отказе записи, ошибки сохранения Start/Stop и
 * одноразовая миграция. Сеть и владелец токена подменены.
 * Многопоточные сценарии используют управляемые latch-точки, без sleep.
 * «Прерывание процесса» моделируется сохранённым снимком — не реальный kill.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceOwnershipTest {

    private Context context;
    private SharedPreferences prefs;
    private List<String> peers;
    private List<Integer> rids;
    private java.util.function.Function<String, SendResult> rule;
    private List<TreatmentEvent> treatments;
    private final List<String> writes = new ArrayList<>();
    private boolean failWrites;
    private volatile Runnable onWrite;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        VkSenderService.resetTransmissionFlagsForTest();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_1");
        TokenStorage.setChatId(context, 777);
        writes.clear();
        failWrites = false;
        VkSenderService.stateStoreOverride = new VkSenderService.StateStore() {
            @Override
            public String read() {
                return writes.isEmpty() ? "" : writes.get(writes.size() - 1);
            }

            @Override
            public boolean write(String json) {
                Runnable hook = onWrite;
                if (hook != null) {
                    hook.run(); // попытка записи — тоже наблюдаемое событие
                }
                if (failWrites) {
                    return false;
                }
                writes.add(json);
                return true;
            }
        };
        peers = Collections.synchronizedList(new ArrayList<>());
        rids = Collections.synchronizedList(new ArrayList<>());
        rule = null;
        treatments = Collections.emptyList();
        VkSenderService.identityVerifierOverride = (token) -> "user:1";
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            return rule != null ? rule.apply(peer) : SendResult.SENT;
        };
        VkSenderService.bgDataOverride = Collections::emptyList;
        VkSenderService.treatmentsOverride = () -> treatments;
    }

    @After
    public void tearDown() {
        VkSenderService.awaitSwitchIdleForTest(); // задачи переключения не перетекают в следующий тест
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        VkSenderService.stateStoreOverride = null;
        VkSenderService.bgDataOverride = null;
        VkSenderService.treatmentsOverride = null;
        VkSenderService.transmissionWriteOverride = null;
        VkSenderService.resetTransmissionFlagsForTest();
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class).create().get();
        idle(s);
        return s;
    }

    private void idle(VkSenderService s) {
        try {
            android.os.Looper l = s.workerLooperForTest();
            if (l != null) {
                Shadows.shadowOf(l).idle();
            }
        } catch (IllegalStateException e) {
            // looper уже завершён после quitSafely — задач больше нет
        }
    }

    private Intent autoUpdate(double bg, long ts) {
        Intent i = new Intent(context, VkSenderService.class);
        i.putExtra("auto_update", true);
        i.putExtra("bg_value", bg);
        i.putExtra("timestamp", ts);
        i.putExtra("direction", "Flat");
        return i;
    }

    private Intent pollNow() {
        Intent i = new Intent(context, VkSenderService.class);
        i.putExtra("poll_now", true);
        return i;
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) context);
    }

    private void awaitLatch(CountDownLatch l) {
        try {
            assertTrue("ожидание управляемой точки превышено",
                    l.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("прервано ожидание latch");
        }
    }

    /**
     * Барьер на worker-потоке сервиса: маркер исполняется строго после всех
     * уже поставленных задач — детерминированное ожидание без sleep.
     * Worker — настоящий HandlerThread; idle() его задач не ждёт.
     */
    private void awaitWorker(VkSenderService s) {
        android.os.Looper l = s.workerLooperForTest();
        CountDownLatch barrier = new CountDownLatch(1);
        new android.os.Handler(l).post(barrier::countDown);
        awaitLatch(barrier);
    }

    /** Пользовательская остановка с ожиданием подтверждённой записи. */
    private void stopTransmission() {
        CountDownLatch l = new CountDownLatch(1);
        VkSenderService.requestStopTransmission(context, ok -> l.countDown());
        awaitLatch(l);
    }

    private void startTransmission() {
        VkSenderService.requestStartTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
    }

    // ---------- 1. Передача владения состоянием между экземплярами ----------

    /**
     * Старая попытка удерживается незавершённой на отдельном потоке.
     * Stop → Start создаёт новый экземпляр, который обязан ждать передачи
     * владения: до завершения старой попытки он не шлёт и не меняет очередь.
     */
    private void handoverScenario(SendResult oldResult) {
        long now = System.currentTimeMillis();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch sendRelease = new CountDownLatch(1);
        CountDownLatch newOwnerSent = new CountDownLatch(1);
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            sendStarted.countDown();
            if (peers.size() >= 2) {
                newOwnerSent.countDown(); // попытка нового владельца
            } else {
                try {
                    sendRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return oldResult;
        };

        VkSenderService s1 = startService();
        s1.onStartCommand(autoUpdate(120, now - 60_000), 0, 1);
        // worker s1 — настоящий поток: send удерживается незавершённым
        awaitLatch(sendStarted);
        int writesBefore = writes.size(); // checkpoint до send уже на диске

        // пользователь: Stop → Start
        stopTransmission();
        s1.onDestroy(); // хвостовая задача встаёт за текущий drain
        startTransmission();
        // «новое сообщение» для нового владельца — кандидат-показание
        prefs.edit()
                .putFloat("candidate_bg_value", 130f)
                .putLong("candidate_bg_timestamp", now - 10_000)
                .putString("candidate_bg_direction", "Flat")
                .commit();

        VkSenderService s2 = Robolectric.buildService(VkSenderService.class)
                .create().get();

        // Пока старый владелец не закончил: новый не отправил ничего,
        // координатор не загружен, записей новых нет.
        assertEquals("новый владелец отправил до передачи владения: " + peers,
                1, peers.size());
        assertTrue(s2.pendingForTest().isEmpty());
        assertEquals(writesBefore, writes.size());

        sendRelease.countDown(); // старая попытка завершается и сохраняется
        awaitLatch(newOwnerSent); // владение передано — новый владелец отправил
    }

    @Test
    public void handoverSentResultNewQueueNotOverwritten() {
        handoverScenario(SendResult.SENT);
        // msg1 подтверждена SENT старым владельцем; новый владелец отправил
        // только новое сообщение и не воскресил msg1
        assertEquals(2, peers.size());
        assertNotEquals(rids.get(0), rids.get(1));
    }

    @Test
    public void handoverRetryableResultKeepsRidAndBindings() {
        handoverScenario(SendResult.RETRYABLE);
        // msg1 осталась в очереди (RETRYABLE, backoff в будущем — не отправлена
        // повторно), новое сообщение отправлено; rid и привязки msg1 целы
        assertEquals(2, peers.size());
        String last = writes.get(writes.size() - 1);
        assertTrue("msg1 с прежним random_id в снимке",
                last.contains("\"rid\":" + rids.get(0)));
        assertTrue(last.contains("\"snd\":\"user:1\""));
        assertTrue(last.contains("\"po\":\"user:1\""));
    }

    @Test
    public void repeatedStopStartNeverTwoSenders() {
        // Два цикла Stop→Start при удержанной попытке: конкурентных отправок нет.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstReturned = new CountDownLatch(1);
        long now = System.currentTimeMillis();

        VkSenderService.senderOverride = (peer, text, rid) -> {
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            peers.add(peer);
            rids.add(rid);
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                firstReturned.countDown();
            }
            inFlight.decrementAndGet();
            return SendResult.SENT;
        };

        VkSenderService s1 = startService();
        s1.onStartCommand(autoUpdate(120, now - 60_000), 0, 1);
        awaitLatch(entered); // первая попытка удерживается

        // Stop → Start → Stop → Start подряд при удержанной первой попытке
        stopTransmission();
        s1.onDestroy();
        startTransmission();
        VkSenderService s2 = Robolectric.buildService(VkSenderService.class)
                .create().get();
        stopTransmission();
        s2.onDestroy();
        startTransmission();
        VkSenderService s3 = Robolectric.buildService(VkSenderService.class)
                .create().get();

        release.countDown();
        awaitLatch(firstReturned); // начатая попытка завершилась
        s3.onDestroy();
        assertEquals("обнаружено два одновременно отправляющих владельца",
                1, maxInFlight.get());
    }

    @Test
    public void oldDestroyDoesNotStealNewAlarmNotificationOrRunning() {
        CountDownLatch sent = new CountDownLatch(1);
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            sent.countDown();
            return SendResult.SENT;
        };
        VkSenderService s1 = startService(); // владеет и активен
        // Новый активный экземпляр: его init на worker-потоке ждёт владения
        VkSenderService s2 = Robolectric.buildService(VkSenderService.class)
                .create().get();

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // getNextScheduledAlarm() в Robolectric изымает будильник — используем список
        int alarmsBefore = Shadows.shadowOf(am).getScheduledAlarms().size();
        assertTrue("будильник нового экземпляра запланирован", alarmsBefore > 0);
        // Работа для нового владельца — записана ДО освобождения владения:
        // хвост s1 исполняется на его реальном потоке, момент не контролируем.
        prefs.edit()
                .putFloat("candidate_bg_value", 130f)
                .putLong("candidate_bg_timestamp", System.currentTimeMillis() - 10_000)
                .putString("candidate_bg_direction", "Flat")
                .commit();

        s1.onDestroy(); // старый уходит — уведомление/будильник нового не его

        // Явная отмена (cancelAlarms + nm.cancel) и сброс isRunning завязаны на
        // wasActive — доказываем их пропуском. Само foreground-уведомление
        // Robolectric снимает в destroy-пути (модель жизненного цикла), этот
        // механизм в тесте отличить от явного cancel нельзя.
        assertTrue("старый onDestroy сбросил состояние нового экземпляра",
                VkSenderService.isRunning());
        assertEquals("старый onDestroy отменил будильник нового",
                alarmsBefore, Shadows.shadowOf(am).getScheduledAlarms().size());

        awaitLatch(sent); // новый владелец получил владение и отправил кандидата
        assertEquals("новый владелец после передачи работает", 1, peers.size());
        s2.onDestroy();
        idle(s2);
    }

    // ---------- 2. Baseline терапии при отказе записи ----------

    private TreatmentEvent event(String id, long createdAt) {
        return new TreatmentEvent(id, createdAt, 0, 1.0, "insulin");
    }

    @Test
    public void baselineWriteFailureKeepsFlagAndRebaselines() {
        prefs.edit().putBoolean("show_treatments", true).commit();
        long now = System.currentTimeMillis();

        VkSenderService s = startService();
        treatments = Collections.singletonList(event("e0", now - 60_000));
        s.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s); // baseline e0 записан
        assertEquals(0, peers.size());
        stopTransmission();
        s.onDestroy();

        // за паузу появился e1; baseline_pending установлен запуском
        treatments = Arrays.asList(
                event("e0", now - 60_000), event("e1", now - 30_000));
        startTransmission();
        failWrites = true; // диск отказал при записи нового baseline

        VkSenderService s2 = startService();
        s2.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s2); // все попытки записи baseline — отказы
        assertTrue("признак baseline снят без подтверждённой записи",
                prefs.getBoolean("treatment_baseline_pending", false));
        s2.onDestroy();

        // восстановление из последнего подтверждённого снимка (без e1 в baseline)
        failWrites = false;
        VkSenderService s3 = startService();
        s3.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s3);
        assertEquals("события за паузу не ушли бэклогом после восстановления",
                0, peers.size());
        assertFalse(prefs.getBoolean("treatment_baseline_pending", false));
        s3.onDestroy();
    }

    @Test
    public void baselineSavedThenFlagLossRebaselinesWithoutBacklog() {
        prefs.edit().putBoolean("show_treatments", true).commit();
        long now = System.currentTimeMillis();

        VkSenderService s = startService();
        treatments = Collections.singletonList(event("e0", now - 60_000));
        s.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s);
        stopTransmission();
        s.onDestroy();

        treatments = Arrays.asList(
                event("e0", now - 60_000), event("e1", now - 30_000));
        startTransmission();
        VkSenderService s2 = startService();
        s2.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s2);
        assertFalse(prefs.getBoolean("treatment_baseline_pending", false));
        assertEquals(0, peers.size());

        // МОДЕЛЬ прерывания: baseline записан, а снятие флага потеряно
        // (падение между checkpoint и commit снятия). Флаг «вернулся».
        prefs.edit().putBoolean("treatment_baseline_pending", true).commit();
        s2.onDestroy();

        treatments = Arrays.asList(
                event("e0", now - 60_000), event("e1", now - 30_000),
                event("e2", now - 10_000));
        VkSenderService s3 = startService();
        s3.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s3);
        assertEquals("повторный baseline поглощает снимок — бэклога нет",
                0, peers.size());
        s3.onDestroy();
    }

    @Test
    public void newEventAfterCompletedBaselineSentOnce() {
        prefs.edit().putBoolean("show_treatments", true).commit();
        long now = System.currentTimeMillis();

        VkSenderService s = startService();
        treatments = Arrays.asList(
                event("e0", now - 60_000), event("e1", now - 30_000));
        startTransmission();
        s.onStartCommand(pollNow(), 0, 1);
        awaitWorker(s);
        assertEquals(0, peers.size()); // baseline поглощён

        treatments = Arrays.asList(
                event("e0", now - 60_000), event("e1", now - 30_000),
                event("e2", now));
        s.onStartCommand(pollNow(), 0, 2);
        awaitWorker(s);
        assertEquals(1, peers.size()); // e2 отправлен однажды

        s.onStartCommand(pollNow(), 0, 3); // тот же снимок — повтора нет
        awaitWorker(s);
        assertEquals(1, peers.size());
        s.onDestroy();
    }

    // ---------- 3. Ошибки сохранения пользовательского выбора ----------

    @Test
    public void startWriteFailureDoesNotStartService() {
        VkSenderService.transmissionWriteOverride = () -> false; // диск отказал
        AtomicBoolean ok = new AtomicBoolean(true);
        VkSenderService.requestStartTransmission(context, ok::set);
        VkSenderService.awaitSwitchIdleForTest();

        assertFalse("результат запуска честно отрицательный", ok.get());
        assertNull("сервис не запрошен без подтверждённого разрешения",
                shadowApp().getNextStartedService());
    }

    @Test
    public void startWriteFailureWithChangedMemoryValueStillNotStarted() {
        // commit «неудачен», но значение в памяти уже true — повторное чтение
        // prefs не доказывает успешную запись: доверяем только коду возврата.
        VkSenderService.transmissionWriteOverride = () -> {
            prefs.edit().putBoolean(
                    VkSenderService.PREF_TRANSMISSION_ENABLED, true).apply();
            return false;
        };
        AtomicBoolean ok = new AtomicBoolean(true);
        VkSenderService.requestStartTransmission(context, ok::set);
        VkSenderService.awaitSwitchIdleForTest();

        assertFalse(ok.get());
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void staleStartSupersededByStopKeepsStopped() {
        // Запись Start удерживается: за это время приходит Stop.
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch writeRelease = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        // Подмена выполняет реальную запись: override — BooleanSupplier без
        // аргументов, порядок вызовов известен (0 = Start→true, 1 = Stop→false).
        VkSenderService.transmissionWriteOverride = () -> {
            if (writeCalls.getAndIncrement() == 0) {
                writeStarted.countDown();
                try {
                    writeRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return prefs.edit()
                        .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true)
                        .commit();
            }
            return prefs.edit()
                    .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false)
                    .commit();
        };

        AtomicBoolean startResult = new AtomicBoolean(true);
        VkSenderService.requestStartTransmission(context, startResult::set);
        awaitLatch(writeStarted); // старый Start внутри записи

        VkSenderService.requestStopTransmission(context); // новый Stop — немедленный запрет
        writeRelease.countDown();
        VkSenderService.awaitSwitchIdleForTest();

        assertFalse("устаревший Start не объявлен успешным", startResult.get());
        assertFalse("передача остаётся выключенной",
                VkSenderService.isTransmissionAllowed(context));
        assertFalse(prefs.getBoolean(
                VkSenderService.PREF_TRANSMISSION_ENABLED, true));
        assertNull("устаревший Start не запустил сервис",
                shadowApp().getNextStartedService());
    }

    @Test
    public void failedStopThenFailedStartKeepsProcessBlock() {
        VkSenderService.transmissionWriteOverride = () -> false;
        AtomicBoolean stopSaved = new AtomicBoolean(true);
        VkSenderService.requestStopTransmission(context, stopSaved::set);
        VkSenderService.awaitSwitchIdleForTest();
        assertFalse(stopSaved.get());

        AtomicBoolean startOk = new AtomicBoolean(true);
        VkSenderService.requestStartTransmission(context, startOk::set);
        VkSenderService.awaitSwitchIdleForTest();
        assertFalse(startOk.get());

        // неуспешный Start процессный запрет не снимает
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }

    // ---------- 4. Одноразовая миграция ----------

    @Test
    public void newInstallConfiguringWithoutStartStaysOff() {
        // новая ненастроенная установка: ключа нет → выключено
        TokenStorage.setToken(context, "");
        TokenStorage.setChatId(context, 0);
        assertFalse(VkSenderService.isTransmissionAllowed(context));
        VkSenderService.awaitSwitchIdleForTest(); // инициализация записана

        // заполнение настроек без «Запустить» выбор не меняет
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_2");
        TokenStorage.setChatId(context, 999);
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }

    @Test
    public void existingInstallMigrationSurvivesChatReset() {
        // существующая настроенная установка без ключа → разрешено (миграция)
        assertFalse(prefs.contains(VkSenderService.PREF_TRANSMISSION_ENABLED));
        assertTrue(VkSenderService.isTransmissionAllowed(context));
        VkSenderService.awaitSwitchIdleForTest();

        // сброс/смена чатов (смена аккаунта) — не остановка пользователем
        TokenStorage.setChatId(context, 0);
        TokenStorage.setAlertChatId(context, 0);
        assertTrue(VkSenderService.isTransmissionAllowed(context));
    }

    @Test
    public void savedChoiceNotOverriddenByMigration() {
        // сохранённый false при полной настройке
        prefs.edit().putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false).commit();
        assertFalse(VkSenderService.isTransmissionAllowed(context));
        // сохранённый true при убранной конфигурации
        prefs.edit().putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true).commit();
        TokenStorage.setToken(context, "");
        TokenStorage.setChatId(context, 0);
        assertTrue(VkSenderService.isTransmissionAllowed(context));
    }

    @Test
    public void newInstanceReadsSavedChoiceWithoutStaticFlags() {
        prefs.edit().putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }
}
