package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.Service;
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

/**
 * №2Б.4: постоянное состояние «Остановлено». Сохранённый запрет —
 * SharedPreferences transmission_enabled; проверяется receivers до
 * WakeLock, сервисом в onCreate/onStartCommand и перед каждой сетевой
 * попыткой после checkpoint. Сеть и владелец токена подменены.
 * «Перезапуск» моделируется новым экземпляром сервиса — не реальный kill.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceStoppedTest {

    private Context context;
    private SharedPreferences prefs;
    private List<String> peers;
    private List<Integer> rids;
    private java.util.function.Function<String, SendResult> rule;
    private java.util.function.Function<String, String> verifier;
    private List<TreatmentEvent> treatments;

    /** История записей состояния — checkpoint может вызывать действие внутри. */
    private final List<String> writes = new ArrayList<>();
    private Runnable onWrite;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        VkSenderService.resetTransmissionFlagsForTest();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_1");
        TokenStorage.setChatId(context, 777);
        writes.clear();
        onWrite = null;
        VkSenderService.stateStoreOverride = new VkSenderService.StateStore() {
            @Override
            public String read() {
                return writes.isEmpty() ? "" : writes.get(writes.size() - 1);
            }

            @Override
            public boolean write(String json) {
                writes.add(json);
                if (onWrite != null) {
                    onWrite.run();
                }
                return true;
            }
        };
        peers = new ArrayList<>();
        rids = new ArrayList<>();
        rule = null;
        verifier = (token) -> "user:1";
        treatments = Collections.emptyList();
        VkSenderService.identityVerifierOverride = (token) -> verifier.apply(token);
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

    private void idleFor(VkSenderService s, java.time.Duration d) {
        try {
            android.os.Looper l = s.workerLooperForTest();
            if (l != null) {
                Shadows.shadowOf(l).idleFor(d);
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

    /** Пользовательская остановка: запрет сохранён, экземпляр завершён. */
    private void stopTransmission(VkSenderService s) {
        assertTrue(VkSenderService.requestStopTransmission(context));
        if (s != null) {
            s.onDestroy();
            idle(s);
        }
    }

    /** Обнулить backoff в последнем сохранённом снимке (модель «время прошло»). */
    private void clearBackoffInSnapshot() {
        int last = writes.size() - 1;
        writes.set(last, writes.get(last).replaceAll("\"nb\":\\d+", "\"nb\":0"));
    }

    // ---------- миграция ----------

    @Test
    public void migrationExistingInstallDefaultsEnabled() {
        // ключа нет, но заданы токен и обычный чат — прежнее поведение
        assertFalse(prefs.contains(VkSenderService.PREF_TRANSMISSION_ENABLED));
        assertTrue(VkSenderService.isTransmissionAllowed(context));
    }

    @Test
    public void migrationNewInstallDefaultsDisabled() {
        TokenStorage.setToken(context, "");
        TokenStorage.setChatId(context, 0);
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }

    @Test
    public void settingsDoNotChangeSavedChoice() {
        prefs.edit().putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false).commit();
        // смена токена/чата не включает передачу обратно
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_2");
        TokenStorage.setChatId(context, 999);
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }

    // ---------- receivers ----------

    @Test
    public void stoppedBroadcastDoesNotStartServiceOrQueue() {
        stopTransmission(null);
        Intent i = new Intent("com.eveningoutpost.dexdrip.BgEstimate");
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 120.0);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() - 60_000);
        new BgReceiver().onReceive(context, i);

        assertNull("broadcast при запрете не должен запускать сервис",
                shadowApp().getNextStartedService());
        // и не должен писать кандидата, который позже попадёт в очередь
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
    }

    @Test
    public void stoppedAlarmDoesNotStartService() {
        stopTransmission(null);
        new AlarmReceiver().onReceive(context, new Intent());
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void stoppedBootDoesNotStartService() {
        stopTransmission(null);
        // МОДЕЛИРОВАНИЕ boot — прямой вызов receiver, не реальная загрузка ОС
        new BootReceiver().onReceive(context,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void stoppedTokenChangeDoesNotStartService() {
        stopTransmission(null);
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_2");
        VkSenderService.notifyTokenChanged(context); // ручной ввод и импорт — тот же путь
        assertNull("смена токена при запрете не включает передачу",
                shadowApp().getNextStartedService());
    }

    // ---------- сервис ----------

    @Test
    public void stoppedServiceCreatedByStaleIntentSendsNothing() {
        stopTransmission(null);
        // экземпляр создан запоздавшей командой — должен завершиться,
        // а не ожидать данные при запрете
        VkSenderService s = startService();
        assertTrue("экземпляр при запрете запрашивает завершение",
                Shadows.shadowOf(s).isStoppedBySelf());

        int rc = s.onStartCommand(null, 0, 7); // восстановление с null intent
        assertEquals(Service.START_NOT_STICKY, rc);
        assertEquals(0, peers.size());
        s.onDestroy();
        assertFalse(VkSenderService.isRunning());
    }

    @Test
    public void staleQueuedIntentAfterStopDoesNotSend() {
        VkSenderService s = startService();
        // команда принята и ждёт в очереди worker — остановка до её выполнения
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        assertTrue(VkSenderService.requestStopTransmission(context));
        idle(s);
        assertEquals(0, peers.size());
        s.onDestroy();
    }

    @Test
    public void stopInsideCheckpointSkipsSend() {
        VkSenderService s = startService();
        // stop прямо внутри checkpoint-записи: после неё send не начинается
        onWrite = () -> {
            onWrite = null;
            VkSenderService.requestStopTransmission(context);
        };
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(0, peers.size());
        assertEquals(1, s.pendingForTest().size()); // сообщение не потеряно
        s.onDestroy();
    }

    @Test
    public void stopDuringFirstSendAppliesResultSkipsRest() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();
        final boolean[] first = {true};
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            if (first[0]) {
                first[0] = false;
                VkSenderService.requestStopTransmission(context); // во время send
            }
            return SendResult.SENT;
        };
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals("второе сообщение после остановки не отправлено",
                1, peers.size());
        assertEquals(1, s.pendingForTest().size()); // тревога осталась в очереди
        assertEquals(0, s.pendingForTest().get(0).attempts);
        // результат первой попытки сохранён: в последнем снимке её нет
        assertTrue(writes.get(writes.size() - 1).contains("\"key\":\"alert:"));
        s.onDestroy();
    }

    @Test
    public void staleDrainCallbackAfterStopDoesNotResume() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.RETRYABLE; // сообщение останется, drain запланирован
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        assertTrue(s.lastDrainDelayMs > 0);

        assertTrue(VkSenderService.requestStopTransmission(context));
        long delayAfterStop = s.lastDrainDelayMs;
        idleFor(s, java.time.Duration.ofSeconds(60)); // старый callback срабатывает
        assertEquals("callback после остановки не возобновляет отправки",
                1, peers.size());
        assertEquals("и не перепланирует drain", delayAfterStop, s.lastDrainDelayMs);
        s.onDestroy();
    }

    @Test
    public void newInstanceReadsSavedStopWithoutStaticState() {
        // запрет записан напрямую (без процессного флага) — новый экземпляр
        // обязан прочитать его с диска
        prefs.edit().putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false).commit();
        VkSenderService.resetTransmissionFlagsForTest();

        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(0, peers.size());
        assertTrue(Shadows.shadowOf(s).isStoppedBySelf());
        s.onDestroy();
    }

    @Test
    public void explicitStartResumesQueueWithSameRandomId() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.RETRYABLE;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        int rid = rids.get(0);
        stopTransmission(s);

        // модель: backoff истёк за время остановки
        clearBackoffInSnapshot();
        rule = null;
        VkSenderService.requestStartTransmission(context);

        VkSenderService s2 = startService();
        assertEquals(2, peers.size());
        assertEquals("очередь возобновлена с прежним random_id",
                rid, rids.get(1).intValue());
        assertEquals(0, s2.pendingForTest().size());
        s2.onDestroy();
    }

    @Test
    public void expiredDuringPauseNotSentAfterResume() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.RETRYABLE;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        stopTransmission(s);

        // модель: срок сообщения истёк за время остановки
        int last = writes.size() - 1;
        writes.set(last, writes.get(last).replaceAll("\"exp\":\\d+", "\"exp\":1"));
        rule = null;
        VkSenderService.requestStartTransmission(context);

        VkSenderService s2 = startService();
        assertEquals("просроченное за время остановки не отправляется",
                1, peers.size());
        assertEquals(0, s2.pendingForTest().size());
        s2.onDestroy();
    }

    @Test
    public void stopWriteFailureIsHonestAndStillBlocks() {
        VkSenderService.transmissionWriteOverride = () -> false; // диск отказал
        boolean saved = VkSenderService.requestStopTransmission(context);
        assertFalse("отказ записи возвращается честно", saved);
        // текущий процесс всё равно блокирует передачу
        assertFalse(VkSenderService.isTransmissionAllowed(context));
    }

    // ---------- терапия за время остановки ----------

    private TreatmentEvent event(String id, long createdAt) {
        return new TreatmentEvent(id, createdAt, 0, 1.0, "insulin");
    }

    @Test
    public void treatmentsDuringPauseDoNotBacklogAfterResume() {
        prefs.edit().putBoolean("show_treatments", true).commit();
        long now = System.currentTimeMillis();

        VkSenderService s = startService();
        treatments = Arrays.asList(event("e0", now - 60_000));
        s.onStartCommand(pollNow(), 0, 1);
        idle(s);
        assertEquals("первый снимок — baseline, без отправок", 0, peers.size());
        stopTransmission(s);

        // за время остановки в xDrip появились события e1, e2
        treatments = Arrays.asList(
                event("e0", now - 60_000),
                event("e1", now - 30_000),
                event("e2", now - 20_000));
        VkSenderService.requestStartTransmission(context);

        VkSenderService s2 = startService();
        s2.onStartCommand(pollNow(), 0, 1);
        idle(s2);
        assertEquals("события за паузу — baseline, не бэклог", 0, peers.size());

        // новое событие после возобновления отправляется обычным путём
        treatments = Arrays.asList(
                event("e0", now - 60_000),
                event("e1", now - 30_000),
                event("e2", now - 20_000),
                event("e3", now));
        s2.onStartCommand(pollNow(), 0, 2);
        idle(s2);
        assertEquals(1, peers.size());
        s2.onDestroy();
    }

    @Test
    public void nullTreatmentsDoNotFinishResumeBaseline() {
        prefs.edit().putBoolean("show_treatments", true).commit();
        VkSenderService.requestStartTransmission(context);
        assertTrue(prefs.getBoolean("treatment_baseline_pending", false));

        VkSenderService s = startService();
        treatments = null; // ошибка сети — baseline не считается полученным
        VkSenderService.treatmentsOverride = () -> treatments;
        s.onStartCommand(pollNow(), 0, 1);
        idle(s);
        assertTrue("null не завершает ожидание baseline",
                prefs.getBoolean("treatment_baseline_pending", false));
        assertEquals(0, peers.size());

        long now = System.currentTimeMillis();
        treatments = Arrays.asList(event("old", now - 30_000));
        s.onStartCommand(pollNow(), 0, 2);
        idle(s);
        assertFalse(prefs.getBoolean("treatment_baseline_pending", false));
        assertEquals("первый валидный снимок — baseline без отправок",
                0, peers.size());
        s.onDestroy();
    }
}
