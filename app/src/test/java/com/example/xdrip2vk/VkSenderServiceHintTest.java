package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.SendResult;
import com.example.xdrip2vk.core.SgvReading;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Модель «broadcast = недоверенный сигнал»: показания приходят только из
 * веб-сервиса xDrip (подменён через bgDataOverride — сети в тестах нет).
 * Прямой вызов onReceive/onStartCommand не является проверкой системной
 * аутентификации отправителя.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceHintTest {

    private Context context;
    private SharedPreferences prefs;
    private List<String> texts;
    private AtomicLong mono;
    private AtomicInteger fetches;
    private volatile List<SgvReading> webReadings;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true)
                .commit();
        mono = new AtomicLong(0);
        fetches = new AtomicInteger(0);
        webReadings = Collections.emptyList();
        texts = new ArrayList<>();
        VkSenderService.resetTransmissionFlagsForTest();
        VkSenderService.hintClockOverride = mono::get;
        VkSenderService.bgDataOverride = () -> {
            fetches.incrementAndGet();
            return webReadings;
        };
        VkSenderService.treatmentsOverride = Collections::emptyList;
        VkSenderService.senderOverride = (peer, text, rid) -> {
            texts.add(text);
            return SendResult.SENT;
        };
        VkSenderService.identityVerifierOverride = t -> "user:1";
        CredHelper.setToken(context, "SYNTHETIC_TOKEN");
        CredHelper.setChatId(context, 777);
    }

    @After
    public void tearDown() {
        VkSenderService.awaitSwitchIdleForTest();
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        VkSenderService.bgDataOverride = null;
        VkSenderService.treatmentsOverride = null;
        VkSenderService.stateStoreOverride = null;
        VkSenderService.transmissionWriteOverride = null;
        VkSenderService.hintClockOverride = null;
        VkSenderService.resetTransmissionFlagsForTest();
    }

    private ShadowApplication shadowApp() {
        return shadowOf((Application) context);
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class)
                .create().get();
        shadowOf(s.workerLooperForTest()).idle();
        return s;
    }

    private void idle(VkSenderService s) {
        shadowOf(s.workerLooperForTest()).idle();
    }

    private Intent hintIntent() {
        return new Intent(context, VkSenderService.class)
                .putExtra("xdrip_hint", true);
    }

    private SgvReading sgv(double mgdl, long ts) {
        return new SgvReading(mgdl, ts, "Flat", "t" + ts);
    }

    // --- сигнал инициирует опрос веб-сервиса, extras не используются ---

    @Test
    public void hintPollsWebServiceIgnoresForgedExtras() {
        // Поддельные extras (999, будущее время, чужой тренд) — НЕ данные.
        Intent forged = new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE);
        forged.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 999);
        forged.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() + 3600_000);
        forged.putExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName", "FORGED");
        new BgReceiver().onReceive(context, forged);
        Intent started = shadowApp().getNextStartedService();
        assertNotNull(started);
        assertTrue(started.getBooleanExtra("xdrip_hint", false));

        // Веб-сервис отвечает 118 — отправляется именно 118 (6.5 mmol).
        webReadings = Collections.singletonList(
                sgv(118, System.currentTimeMillis() - 60_000));
        VkSenderService s = startService();
        s.onStartCommand(started, 0, 1);
        idle(s);

        assertEquals("один опрос веб-сервиса", 1, fetches.get());
        assertEquals(1, texts.size());
        assertTrue("отправлено значение веб-сервиса: " + texts.get(0),
                texts.get(0).contains("6.6"));
        assertFalse("значение из extras не отправлено",
                texts.get(0).contains("55.5"));
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        s.onDestroy();
    }

    @Test
    public void webServiceUnavailableSendsNothing() {
        webReadings = Collections.emptyList(); // пустой ответ
        VkSenderService s = startService();
        s.onStartCommand(hintIntent(), 0, 1);
        idle(s);

        assertTrue("нет данных — нет отправок", texts.isEmpty());
        CharSequence note = s.notificationForTest();
        assertNotNull(note);
        assertTrue("причина понятна пользователю: " + note,
                note.toString().contains("xDrip"));
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));

        // Подмена ошибки веб-сервиса (исключение) — тот же результат.
        VkSenderService.bgDataOverride = () -> {
            throw new RuntimeException("simulated http error");
        };
        mono.addAndGet(16_000);
        s.onStartCommand(hintIntent(), 0, 2);
        idle(s);
        assertTrue("ошибка опроса — отправок нет", texts.isEmpty());
        s.onDestroy();
    }

    @Test
    public void queuedHintCommandsCoalesceInService() {
        webReadings = Collections.singletonList(
                sgv(120, System.currentTimeMillis() - 60_000));
        VkSenderService s = startService();
        // Две команды сигнала подряд — сервис проверяет лимит повторно.
        s.onStartCommand(hintIntent(), 0, 1);
        s.onStartCommand(hintIntent(), 0, 2);
        idle(s);

        assertEquals("второй запрос в окне не создаёт опрос", 1, fetches.get());
        s.onDestroy();
    }

    @Test
    public void hintPollDoesNotPostponePeriodicAlarm() {
        VkSenderService s = startService();
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager sam = Shadows.shadowOf(am);
        assertFalse(sam.getScheduledAlarms().isEmpty());
        long triggerBefore =
                sam.getScheduledAlarms().get(0).triggerAtTime;

        webReadings = Collections.singletonList(
                sgv(120, System.currentTimeMillis() - 60_000));
        s.onStartCommand(hintIntent(), 0, 1);
        idle(s);

        assertEquals(1, fetches.get());
        assertEquals("периодический будильник не отодвинут потоком сигналов",
                triggerBefore,
                sam.getScheduledAlarms().get(0).triggerAtTime);
        s.onDestroy();
    }

    @Test
    public void periodicPollStillWorks() {
        webReadings = Collections.singletonList(
                sgv(122, System.currentTimeMillis() - 60_000));
        VkSenderService s = startService();
        s.onStartCommand(new Intent(context, VkSenderService.class)
                .putExtra("poll_now", true), 0, 1);
        idle(s);

        assertEquals(1, fetches.get());
        assertEquals(1, texts.size());
        s.onDestroy();
    }

    @Test
    public void stopBeforeHintProcessingKeepsDenied() {
        VkSenderService s = startService();
        // Блокировка гарантирует: hint-команда ещё ждёт, когда приходит Stop —
        // иначе worker может исполнить её раньше (гонка на быстрых машинах).
        CountDownLatch release = new CountDownLatch(1);
        new android.os.Handler(s.workerLooperForTest())
                .post(() -> await(release));
        s.onStartCommand(hintIntent(), 0, 1); // команда ждёт worker за блокировкой
        // Stop до обработки — производственный путь; запрет действует при
        // исполнении задачи.
        VkSenderService.requestStopTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
        release.countDown();
        idle(s);

        assertEquals(0, fetches.get());
        assertTrue(texts.isEmpty());
        s.onDestroy();
    }

    private void await(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void sameWebReadingNotDuplicated() {
        long ts = System.currentTimeMillis() - 60_000;
        webReadings = Collections.singletonList(sgv(118, ts));
        VkSenderService s = startService();
        s.onStartCommand(hintIntent(), 0, 1);
        idle(s);
        assertEquals(1, texts.size());

        mono.addAndGet(16_000);
        VkSenderService.consumeHintForTest();
        s.onStartCommand(hintIntent(), 0, 2);
        idle(s);
        assertEquals(2, fetches.get());
        assertEquals("повтор того же показания — без дубля", 1, texts.size());
        s.onDestroy();
    }

    @Test
    public void manualSendUsesWebServiceNotOldCache() {
        // Старый UI-кеш неизвестного происхождения — не источник данных.
        prefs.edit()
                .putFloat("last_bg_value", 200f)
                .putLong("last_bg_timestamp", System.currentTimeMillis() - 60_000)
                .putString("last_bg_direction", "Flat")
                .commit();
        VkSenderService s = startService();
        s.onStartCommand(new Intent(context, VkSenderService.class)
                .putExtra("manual_send", true), 0, 1);
        idle(s);
        assertEquals(1, fetches.get());
        assertTrue("нет ответа веб-сервиса — кеш не отправлен", texts.isEmpty());

        // С ответом веб-сервиса ручная отправка создаёт сообщение.
        webReadings = Collections.singletonList(
                sgv(118, System.currentTimeMillis() - 60_000));
        s.onStartCommand(new Intent(context, VkSenderService.class)
                .putExtra("manual_send", true), 0, 2);
        idle(s);
        assertEquals(1, texts.size());
        assertTrue(texts.get(0).contains("6.6"));

        // Ручная отправка того же доставленного показания — новое сообщение.
        s.onStartCommand(new Intent(context, VkSenderService.class)
                .putExtra("manual_send", true), 0, 3);
        idle(s);
        assertEquals("ручная повторная отправка — отдельное сообщение",
                2, texts.size());
        s.onDestroy();
    }

    @Test
    public void hundredSignalsDoNotSpawnWork() {
        for (int i = 0; i < 100; i++) {
            new BgReceiver().onReceive(context,
                    new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        }
        assertNotNull(shadowApp().getNextStartedService());
        assertNull("серия сигналов объединена до одного запуска",
                shadowApp().getNextStartedService());
    }

    // --- жизненный цикл маркера принятого сигнала ---

    /**
     * Воспроизведение отчёта: hint поставлен в очередь worker → Stop → Start →
     * новый сигнал после интервала должен обрабатываться. Маркер снимается
     * производственным кодом (без consumeHintForTest/reset посередине).
     */
    @Test
    public void cancelledHintDoesNotWedgeChannel() {
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started = shadowApp().getNextStartedService();
        assertNotNull(started);

        VkSenderService s = startService();
        // Блокировка держит worker: hint-команда ждёт обработки, когда
        // приходит Stop — без гонки исполнения до запрета.
        CountDownLatch release = new CountDownLatch(1);
        new android.os.Handler(s.workerLooperForTest())
                .post(() -> await(release));
        s.onStartCommand(started, 0, 1); // команда ждёт worker — не выполняется
        // Stop до выполнения — производственный путь пользователя.
        VkSenderService.requestStopTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
        release.countDown();
        idle(s); // отменённая задача выходит по запрету и снимает маркер
        s.onDestroy();

        // Start — производственный путь.
        VkSenderService.requestStartTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
        assertNotNull(shadowApp().getNextStartedService()); // старт от Start

        mono.addAndGet(16_000);
        webReadings = Collections.singletonList(
                sgv(118, System.currentTimeMillis() - 60_000));
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started2 = shadowApp().getNextStartedService();
        assertNotNull("канал не завис: новый сигнал запускает сервис", started2);

        VkSenderService s2 = startService();
        s2.onStartCommand(started2, 0, 1);
        idle(s2);
        assertEquals("опрос по новому сигналу выполнен", 1, fetches.get());
        assertEquals(1, texts.size());
        s2.onDestroy();
    }

    /**
     * Hint-команда отклонена в onStartCommand при сохранённом запрете — маркер
     * снят на точке отклонения, после возобновления канал работает.
     */
    @Test
    public void hintRejectedAtEntryReleasesMarker() {
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started = shadowApp().getNextStartedService();
        assertNotNull(started);

        // Экземпляр создан при разрешении; Stop — до доставки команды.
        VkSenderService s = startService();
        VkSenderService.requestStopTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
        s.onStartCommand(started, 0, 1); // запрет → отклонение до worker
        s.onDestroy();

        VkSenderService.requestStartTransmission(context);
        VkSenderService.awaitSwitchIdleForTest();
        shadowApp().getNextStartedService();

        mono.addAndGet(16_000);
        webReadings = Collections.singletonList(
                sgv(118, System.currentTimeMillis() - 60_000));
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started2 = shadowApp().getNextStartedService();
        assertNotNull("отклонённая команда не оставила вечный маркер", started2);
        VkSenderService s2 = startService();
        s2.onStartCommand(started2, 0, 1);
        idle(s2);
        assertEquals(1, fetches.get());
        s2.onDestroy();
    }

    /**
     * Ошибка запуска сервиса (ограничение фонового запуска) — маркер снимается,
     * следующий допустимый сигнал обрабатывается.
     */
    @Test
    public void serviceStartFailureReleasesMarker() {
        Context failing = new ContextWrapper(context) {
            @Override
            public ComponentName startForegroundService(Intent intent) {
                throw new RuntimeException("simulated background start restriction");
            }
        };
        VkSenderService.notifyXdripSignal(failing); // исключение поймано, маркер снят

        mono.addAndGet(16_000);
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        assertNotNull("следующий сигнал после ошибки запуска принят",
                shadowApp().getNextStartedService());
    }

    /**
     * Экземпляр завершён до обработки сигнала — маркер его поколения снят,
     * новый экземпляр принимает новые сигналы.
     */
    @Test
    public void destroyedInstanceReleasesPendingHint() {
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started = shadowApp().getNextStartedService();
        assertNotNull(started);

        VkSenderService s = startService();
        s.onStartCommand(started, 0, 1); // поколение записано, команда в очереди
        s.onDestroy();                 // worker уходит до обработки

        mono.addAndGet(16_000);
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        assertNotNull("новый сигнал после гибели экземпляра принят",
                shadowApp().getNextStartedService());
    }

    /**
     * Запоздавшее завершение старого экземпляра не снимает маркер более нового
     * сигнала: gen-проверка защищает новое поколение.
     */
    @Test
    public void staleInstanceDestroyKeepsNewerMarker() {
        webReadings = Collections.singletonList(
                sgv(118, System.currentTimeMillis() - 60_000));
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent i1 = shadowApp().getNextStartedService();
        VkSenderService s1 = startService();
        s1.onStartCommand(i1, 0, 1);
        idle(s1);
        assertEquals(1, fetches.get()); // gen1 обработан s1

        mono.addAndGet(16_000);
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent i2 = shadowApp().getNextStartedService();
        assertNotNull(i2); // gen2 принят, команда ещё не доставлена

        // Запоздавшая отмена старого экземпляра — маркер gen2 должен сохраниться.
        s1.onDestroy();
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        assertNull("маркер нового сигнала сохранён — повторный сигнал объединён",
                shadowApp().getNextStartedService());

        VkSenderService s2 = startService();
        s2.onStartCommand(i2, 0, 1);
        idle(s2);
        assertEquals("gen2 доехал до нового экземпляра и обработан",
                2, fetches.get());
        s2.onDestroy();
    }

    /**
     * Аварийный экземпляр (worker мёртв): команда сигнала отклоняется в
     * onStartCommand с START_STICKY — маркер снимается, канал не зависает.
     */
    @Test
    public void failedWorkerInstanceReleasesHint() {
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        Intent started = shadowApp().getNextStartedService();
        assertNotNull(started);

        VkSenderService s = startService();
        s.workerFailed = true;
        s.onStartCommand(started, 0, 1);

        mono.addAndGet(16_000);
        new BgReceiver().onReceive(context,
                new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE));
        assertNotNull("сигнал после аварийного экземпляра принят",
                shadowApp().getNextStartedService());
        s.onDestroy();
    }
}
