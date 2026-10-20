package com.example.xdrip2vk.max;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MAX-1Б: подтверждённое состояние и восстановление после прерывания.
 * Настоящий MaxNotificationListener + MaxSender + MaxChannel под Robolectric;
 * хранилище подменено fake'ом с отдельным «диском»: удержания commit,
 * отказы записи и новые handle'ы над тем же диском.
 *
 * «Перезапуск процесса» моделируется сборкой снимка из диска заново
 * (MaxChannel.resetForTest + сброс runtime-состояния) — настоящий kill
 * процесса в тестах не выполнялся. Синхронизация — latch'и, без sleep.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaxDurabilityTest {

    /** «Диск» fake-хранилища — разделяется всеми handle'ами одного процесса. */
    static final class FakeDisk {
        final Map<String, Object> data = new HashMap<>();
        final AtomicInteger commits = new AtomicInteger();
        volatile boolean failCommits;           // commit() -> false (запись не применяется)
        volatile Runnable onCommit;             // одноразовый хук внутри commit (на потоке исполнителя)
        volatile CountDownLatch holdStart;      // commit удерживается между latch'ами
        volatile CountDownLatch holdRelease;

        Runnable takeHook() {
            Runnable h = onCommit;
            onCommit = null;
            return h;
        }
    }

    static final class FakeStore implements MaxStore {
        final FakeDisk disk;

        FakeStore(FakeDisk disk) {
            this.disk = disk;
        }

        @Override public String getString(String key, String def) {
            Object v = disk.data.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override public long getLong(String key, long def) {
            Object v = disk.data.get(key);
            return v instanceof Long ? (Long) v : def;
        }

        @Override public int getInt(String key, int def) {
            Object v = disk.data.get(key);
            return v instanceof Integer ? (Integer) v : def;
        }

        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> puts = new HashMap<>();
                private final List<String> removes = new ArrayList<>();

                @Override public Editor putString(String k, String v) { puts.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { puts.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { puts.put(k, v); return this; }
                @Override public Editor remove(String k) { removes.add(k); return this; }

                @Override public boolean commit() {
                    disk.commits.incrementAndGet();
                    Runnable hook = disk.takeHook();
                    if (hook != null) hook.run();
                    if (disk.holdStart != null) {
                        disk.holdStart.countDown();
                        try {
                            disk.holdRelease.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (disk.failCommits) {
                        // Запись не применилась — даже если «кеш» редактора грязный.
                        return false;
                    }
                    disk.data.putAll(puts);
                    for (String k : removes) disk.data.remove(k);
                    return true;
                }
            };
        }
    }

    private Context context;
    private SharedPreferences prefs;
    private MaxNotificationListener listener;
    private FakeDisk disk;
    private List<PendingIntent> sentCommands;
    private List<Intent> sentFillIns;
    private AtomicLong clock;
    private int piSeq;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        context.getSharedPreferences("max_channel", Context.MODE_PRIVATE)
                .edit().clear().commit();
        prefs.edit()
                .putBoolean("transmission_enabled", true)
                .putBoolean(MaxChannel.PREF_MAX_ENABLED, true)
                .commit();
        MaxState.resetForTest();
        MaxChannel.resetForTest();
        MaxState.listenerConnected = true;

        MaxSender.workerForTest = Runnable::run;
        MaxChannel.retrySchedulerForTest = (task, delayMs) -> task.run();
        disk = new FakeDisk();
        MaxChannel.storeFactoryForTest = c -> new FakeStore(disk);

        listener = Robolectric.buildService(MaxNotificationListener.class).create().get();

        sentCommands = new ArrayList<>();
        sentFillIns = new ArrayList<>();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
        };
        MaxSender.creatorPackageForTest = pi -> MaxChannel.MAX_PACKAGE;
        clock = new AtomicLong(1_700_000_000_000L);
        MaxSender.clockForTest = clock::get;
        piSeq = 0;

        android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
        pi.packageName = MaxChannel.MAX_PACKAGE;
        pi.versionName = "1.0";
        pi.versionCode = 1;
        org.robolectric.Shadows.shadowOf(context.getPackageManager())
                .installPackage(pi);

        android.provider.Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners",
                new android.content.ComponentName(context,
                        MaxNotificationListener.class).flattenToString());
    }

    @After
    public void tearDown() {
        MaxSender.sendCommandForTest = null;
        MaxSender.creatorPackageForTest = null;
        MaxSender.clockForTest = null;
        MaxSender.beforeCommandHookForTest = null;
        MaxSender.workerForTest = null;
        MaxChannel.retrySchedulerForTest = null;
        MaxChannel.storeFactoryForTest = null;
        MaxChannel.resetForTest();
        MaxState.resetForTest();
    }

    // --- фабрики -------------------------------------------------------------

    private PendingIntent fakePi() {
        piSeq++;
        return PendingIntent.getBroadcast(context, 2000 + piSeq,
                new Intent("com.example.xdrip2vk.test.DPI_" + piSeq),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private StatusBarNotification maxSbn(String chat, String channel,
                                         int notifId, PendingIntent pi) {
        Bundle extras = new Bundle();
        extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, chat);
        Notification.Action reply = new Notification.Action.Builder(
                null, "Ответить", pi)
                .addRemoteInput(new RemoteInput.Builder("key_text")
                        .setLabel("Ответить").build())
                .build();
        Notification n = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(chat)
                .setExtras(extras)
                .addAction(reply)
                .build();
        return new StatusBarNotification(MaxChannel.MAX_PACKAGE,
                MaxChannel.MAX_PACKAGE, notifId, null, 0, 0, 0, n,
                Process.myUserHandle(), clock.get());
    }

    private void selectPapa() {
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        assertNotNull(MaxChannel.getTarget(context));
    }

    /** Модель перезапуска процесса: снимок и runtime собираются с диска заново. */
    private void restartFromDisk() {
        MaxState.targetButton = null;
        MaxChannel.resetForTest();
    }

    // --- обязательные сценарии -------------------------------------------------

    @Test
    public void reserveWriteFails_NoExternalCommand() {
        selectPapa();
        // Отказ записи перед командой: pending и резерв не подтверждаются.
        disk.failCommits = true;
        MaxSender.onReading(context, "6.1", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxState.storageError);
        assertTrue(MaxSender.statusText(context).contains("Ошибка записи"));
        // Фиксированный статус — без текста показания.
        assertFalse(MaxSender.statusText(context).contains("6.1"));
    }

    @Test
    public void commitFalseNotBypassedByNewHandle() {
        selectPapa();
        disk.failCommits = true;
        MaxSender.onReading(context, "6.2", clock.get());
        assertEquals(0, sentCommands.size());

        // Новый handle того же хранилища — тот же диск, запрет не обходится.
        MaxChannel.storeFactoryForTest = c -> new FakeStore(disk);
        restartFromDisk(); // снимок собран с диска — подтверждённой цель всё ещё нет данных попытки
        MaxSender.onReading(context, "6.2", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxState.storageError);
    }

    @Test
    public void restoredPendingKeepsTargetTextAndTtl() {
        selectPapa();
        // Кнопки нет — показание ложится в pending и подтверждается записью.
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.3", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        // «Перезапуск»: цель, текст, время и поколение pending восстановлены с диска.
        restartFromDisk();
        MaxChannel.Pending p = MaxChannel.getPending(context);
        assertNotNull(p);
        assertEquals("6.3", p.text);
        assertEquals(MaxChannel.getTarget(context).generation, p.targetGeneration);

        // Просроченное показание после восстановления не уходит.
        clock.addAndGet(MaxChannel.PENDING_MAX_AGE_MS + 60_000);
        MaxSender.onButtonCaptured(context);
        assertEquals(0, sentCommands.size());
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void restoredPendingSendsOnceOnFreshButton() {
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.3", clock.get());
        restartFromDisk();
        // Свежая кнопка выбранного чата — ожидающее уходит ровно один раз.
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void resultWriteFailsAfterSend_NoRetryOfCommand() {
        selectPapa();
        // send успешен, а запись итога отказывает — RESERVED остаётся на диске.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            disk.failCommits = true; // следующие commit (итог + повторы) откажут
        };
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals(1, sentCommands.size()); // команда ушла
        disk.failCommits = false;
        disk.onCommit = null;

        // Повторной команды нет — ни от нового события кнопки, ни от того же показания.
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals(1, sentCommands.size());
        // RESERVED переживает «перезапуск» — повтор всё ещё запрещён.
        restartFromDisk();
        MaxSender.onButtonCaptured(context);
        assertEquals(1, sentCommands.size());
        assertTrue(MaxState.outcomeUnknown);
        // Последняя запись отказала — статус ошибки записи показывается первым.
        assertTrue(MaxSender.statusText(context).contains("Ошибка записи"));
        // Первая подтверждённая запись после восстановления снимает ошибку —
        // и статус показывает неизвестный исход зарезервированной попытки.
        MaxChannel.confirmClearBlocked(context);
        assertTrue(MaxSender.statusText(context).contains("неизвестен"));

        // Следующее НОВОЕ показание идёт обычным путём — ровно один раз.
        clock.addAndGet(60_000);
        MaxSender.onReading(context, "6.5", clock.get());
        assertEquals(2, sentCommands.size());
    }

    @Test
    public void reservedAttemptNotRetriedAfterRestart() {
        // «Процесс умер между резервом и итогом»: на диске RESERVED попытка
        // и ожидающее того же показания — автоматический повтор запрещён.
        selectPapa();
        long ts = clock.get();
        MaxSender.onReading(context, "6.6", ts);
        assertEquals(1, sentCommands.size()); // первое показание передано успешно

        // Моделируем незавершённую попытку: RESERVED + pending того же показания.
        MaxStore s = MaxChannel.storeFactoryForTest.apply(context);
        s.edit().putInt("attempt_state", MaxChannel.Attempt.STATE_RESERVED)
                .putString("pending_text", "6.6")
                .putLong("pending_reading_ts", ts)
                .putLong("pending_gen", MaxChannel.getTarget(context).generation)
                .commit();
        restartFromDisk();
        assertNotNull(MaxChannel.getPending(context)); // триггер загрузки снимка
        assertTrue(MaxState.outcomeUnknown);

        // Новая кнопка выбранного чата: pending не повторяет команду с
        // неизвестным исходом — внешних команд по-прежнему одна.
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("неизвестен"));
    }

    @Test
    public void confirmedResultSurvivesRestart() {
        selectPapa();
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(1, sentCommands.size());
        assertNull(MaxChannel.getPending(context));

        restartFromDisk();
        // Подтверждённый handoff сохранился — то же показание не повторяется.
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(1, sentCommands.size());
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getAttempt(context).state);
    }

    /** Хук именно внутри commit резерва попытки (второй commit цепочки). */
    private void hookOnReserveCommit(Runnable hook) {
        disk.onCommit = () -> disk.onCommit = hook;
    }

    @Test
    public void expiredDuringCommit_NoCommand() {
        selectPapa();
        // Внутри commit резерва «истёк срок»: часы прыгают за TTL.
        hookOnReserveCommit(() -> clock.addAndGet(MaxChannel.PENDING_MAX_AGE_MS + 60_000));
        MaxSender.onReading(context, "6.8", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void stopDuringCommit_NoCommand() {
        selectPapa();
        // Внутри commit резерва пришёл общий Stop.
        hookOnReserveCommit(() ->
                prefs.edit().putBoolean("transmission_enabled", false).commit());
        MaxSender.onReading(context, "6.9", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void conflictDuringCommit_NoCommand() {
        selectPapa();
        // Внутри commit резерва настоящий listener обрабатывает чужой чат
        // с равной операцией ответа — конфликт до начала команды.
        PendingIntent savedPi = MaxState.targetButton.pendingIntent;
        hookOnReserveCommit(() -> listener.onNotificationPosted(
                maxSbn("Мама", "ru.oneme.app.dialogs", 555, savedPi)));
        MaxSender.onReading(context, "7.0", clock.get());
        assertEquals(0, sentCommands.size());
        assertEquals(MaxChannel.BLOCKED_CONFLICT, MaxChannel.getBlockedReason(context));
        assertNotNull("ожидающее показание сохранено", MaxChannel.getPending(context));
    }

    @Test
    public void targetChangeDropsOldPending() {
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "7.1", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        // Смена цели — одна подтверждённая запись: новое поколение + снятие pending.
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        assertNull(MaxChannel.getPending(context));
        long genMama = MaxChannel.getTarget(context).generation;

        // «Перезапуск»: старое pending не восстанавливается и новому чату не достаётся.
        restartFromDisk();
        assertNull(MaxChannel.getPending(context));
        assertEquals(genMama, MaxChannel.getTarget(context).generation);
        MaxSender.onButtonCaptured(context);
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void newerPendingSurvivesOldAttemptFinish() {
        selectPapa();
        // В момент «внешней команды» первого показания на диск уже попало более
        // новое ожидающее — завершение старой попытки удаляет только своё.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            // Более новое ожидающее записано прямо на диск между send и итогом.
            MaxStore s = MaxChannel.storeFactoryForTest.apply(context);
            s.edit().putString("pending_text", "7.2")
                    .putLong("pending_reading_ts", clock.get() + 60_000)
                    .putLong("pending_gen", MaxChannel.getTarget(context).generation)
                    .commit();
        };
        MaxSender.onReading(context, "7.1", clock.get());
        assertEquals(1, sentCommands.size());
        // Старое pending снято, новое осталось на диске — и переживает «перезапуск».
        restartFromDisk();
        MaxChannel.Pending p = MaxChannel.getPending(context);
        assertNotNull(p);
        assertEquals("7.2", p.text);
    }

    @Test
    public void listenerDoesNotWaitForHeldCommit() throws Exception {
        selectPapa();
        // Настоящий однопоточный исполнитель; commit удержан latch'ем.
        ExecutorService real = Executors.newSingleThreadExecutor();
        MaxSender.workerForTest = real;
        try {
            disk.holdStart = new CountDownLatch(1);
            disk.holdRelease = new CountDownLatch(1);

            // Показание → запись pending на рабочем потоке — удержана.
            MaxSender.onReading(context, "7.3", clock.get());
            assertTrue("рабочий поток вошёл в удержанную запись",
                    disk.holdStart.await(10, TimeUnit.SECONDS));

            // Слушатель передаёт событие — вызывающий поток не ждёт commit/send.
            CountDownLatch listenerReturned = new CountDownLatch(1);
            new Thread(() -> {
                listener.onNotificationPosted(
                        maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
                listenerReturned.countDown();
            }).start();
            assertTrue("поток слушателя не ждёт удержанную запись",
                    listenerReturned.await(10, TimeUnit.SECONDS));

            disk.holdRelease.countDown();
            // Все задачи worker-а завершились — состояние согласовано.
            real.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(1, sentCommands.size()); // показание ушло после снятия удержания
        } finally {
            real.shutdownNow();
            MaxSender.workerForTest = Runnable::run;
        }
    }

    @Test
    public void retryOfUnconfirmedWriteReallyCommits() {
        selectPapa();
        int before = disk.commits.get();
        // Одна отказная запись, затем хранилище «чинится» — повтор реально commit'ит.
        disk.failCommits = true;
        MaxSender.onReading(context, "7.4", clock.get());
        assertEquals(0, sentCommands.size());
        // MAX_WRITE_ATTEMPTS попыток записи pending действительно прошли.
        assertTrue(disk.commits.get() - before >= MaxChannel.MAX_WRITE_ATTEMPTS);
        disk.failCommits = false;
        // После восстановления хранилища новое событие работает обычно —
        // первая же подтверждённая запись снимает storageError.
        MaxSender.onReading(context, "7.4", clock.get());
        assertEquals(1, sentCommands.size());
    }

    @Test
    public void testMessageStillKeepsPendingSugar() {
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "7.5", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        MaxSender.Candidate c = MaxSender.candidates().get(0);
        MaxState.targetButton = c.button;
        String res = MaxSender.sendTest(context);
        assertTrue(res, res.contains("передано в MAX"));
        assertNotNull("тест не потребляет ожидающий сахар", MaxChannel.getPending(context));
        MaxSender.onButtonCaptured(context);
        assertEquals(2, sentCommands.size());
        assertNull(MaxChannel.getPending(context));
    }
}
