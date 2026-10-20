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

import java.util.ArrayDeque;
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
 * MAX-1Б/1Б.1: подтверждённое состояние, устаревшие повторы записи и
 * восстановление после прерывания.
 *
 * Хранилище — fake с честной моделью SharedPreferences: общий процессный
 * кеш для всех handle'ов + отдельный подтверждённый диск. commit=false и
 * исключение происходят ПОСЛЕ изменения кеша, как память SharedPreferences
 * при неудачной записи. «Новый процесс» моделируется env.newProcess():
 * кеш пересобирается только из диска, процессные флаги сбрасываются —
 * настоящий kill процесса в тестах не выполнялся.
 *
 * Отложенные повторы записи — управляемый планировщик: задачи складываются
 * в очередь и запускаются тестом вручную. Синхронизация — latch'и, без sleep.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaxDurabilityTest {

    /** Окружение хранилища: диск + процессный кеш + управление отказами. */
    static final class FakeEnv {
        final Map<String, Object> disk = new HashMap<>();   // подтверждённое
        final Map<String, Object> cache = new HashMap<>();  // кеш процесса — общий для handle'ов
        final AtomicInteger commits = new AtomicInteger();
        volatile int failMode;                  // 0 — ok, 1 — commit=false, 2 — исключение
        volatile Runnable onCommit;             // одноразовый хук внутри commit
        volatile int holdOnCommit = -1;         // номер commit, который удержать latch'ем
        volatile CountDownLatch holdStart;
        volatile CountDownLatch holdRelease;

        Runnable takeHook() {
            Runnable h = onCommit;
            onCommit = null;
            return h;
        }

        /** «Новый процесс»: кеш уничтожен и пересобран только из диска. */
        void newProcess() {
            cache.clear();
            cache.putAll(disk);
        }
    }

    static final class FakeStore implements MaxStore {
        final FakeEnv env;

        FakeStore(FakeEnv env) {
            this.env = env;
        }

        @Override public String getString(String key, String def) {
            Object v = env.cache.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override public long getLong(String key, long def) {
            Object v = env.cache.get(key);
            return v instanceof Long ? (Long) v : def;
        }

        @Override public int getInt(String key, int def) {
            Object v = env.cache.get(key);
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
                    int n = env.commits.incrementAndGet();
                    // Кеш процесса меняется до исхода записи — как память
                    // SharedPreferences: отказ/исключение оставляет кеш грязным.
                    env.cache.putAll(puts);
                    for (String k : removes) env.cache.remove(k);
                    Runnable hook = env.takeHook();
                    if (hook != null) hook.run();
                    if (n == env.holdOnCommit) {
                        env.holdOnCommit = -1;
                        env.holdStart.countDown();
                        try {
                            env.holdRelease.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (env.failMode == 1) return false;
                    if (env.failMode == 2) throw new RuntimeException("commit failed");
                    env.disk.putAll(puts);
                    for (String k : removes) env.disk.remove(k);
                    return true;
                }
            };
        }
    }

    private Context context;
    private SharedPreferences prefs;
    private MaxNotificationListener listener;
    private FakeEnv env;
    private List<PendingIntent> sentCommands;
    private List<Intent> sentFillIns;
    private AtomicLong clock;
    private int piSeq;
    private final ArrayDeque<Runnable> retryQueue = new ArrayDeque<>();
    private final List<Long> retryDelays = new ArrayList<>();

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
        // Управляемый планировщик: повторы не бегут сами — тест запускает их явно.
        MaxChannel.retrySchedulerForTest = (task, delayMs) -> {
            retryQueue.add(task);
            retryDelays.add(delayMs);
        };
        env = new FakeEnv();
        MaxChannel.storeFactoryForTest = c -> new FakeStore(env);

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

    // --- фабрики ---------------------------------------------------------------

    private PendingIntent fakePi() {
        piSeq++;
        return PendingIntent.getBroadcast(context, 2000 + piSeq,
                new Intent("com.example.xdrip2vk.test.DPI_" + piSeq),
                PendingIntent.FLAG_IMMUTABLE);
    }

    /** Вторая обёртка той же операции PendingIntent: equals=true, ==false. */
    private PendingIntent equalWrapper(PendingIntent original, Intent op) {
        try {
            int requestCode = org.robolectric.util.ReflectionHelpers.getField(
                    org.robolectric.shadow.api.Shadow.extract(original), "requestCode");
            Class<?> senderClass = Class.forName("android.content.IIntentSender");
            Object sender = java.lang.reflect.Proxy.newProxyInstance(
                    senderClass.getClassLoader(), new Class[]{senderClass},
                    (proxy, method, args) -> null);
            PendingIntent copy = org.robolectric.util.ReflectionHelpers.callConstructor(
                    PendingIntent.class,
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                            senderClass, sender));
            Object shadow = org.robolectric.shadow.api.Shadow.extract(copy);
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "savedIntents", new Intent[]{op});
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "savedContext", context);
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "requestCode", requestCode);
            assertTrue(original.equals(copy));
            return copy;
        } catch (Exception e) {
            throw new AssertionError("вторая обёртка операции не создана", e);
        }
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

    private void selectMama() {
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        for (MaxSender.Candidate c : MaxSender.candidates()) {
            if (c.chatName().equals("Мама")) {
                assertTrue(MaxSender.selectCandidate(context, c));
                return;
            }
        }
        fail("кандидат «Мама» не найден");
    }

    /** Модель нового процесса: кеш только из диска, процессные флаги сброшены. */
    private void restartFromDisk() {
        env.newProcess();
        MaxState.targetButton = null;
        MaxState.resetForTest();
        MaxChannel.resetForTest();
        MaxState.listenerConnected = true;
    }

    private void drainRetries() {
        while (!retryQueue.isEmpty()) retryQueue.poll().run();
    }

    private String fillInText(Intent fillIn) {
        Bundle results = RemoteInput.getResultsFromIntent(fillIn);
        CharSequence cs = results == null ? null : results.getCharSequence("key_text");
        return cs == null ? null : cs.toString();
    }

    private List<String> sentTexts() {
        List<String> out = new ArrayList<>();
        for (Intent i : sentFillIns) out.add(fillInText(i));
        return out;
    }

    /** Хук именно внутри commit резерва попытки (второй commit цепочки). */
    private void hookOnReserveCommit(Runnable hook) {
        env.onCommit = () -> env.onCommit = hook;
    }

    // --- отказы записи и модель хранилища -------------------------------------

    @Test
    public void reserveWriteFails_NoExternalCommand() {
        selectPapa();
        env.failMode = 1;
        MaxSender.onReading(context, "6.1", clock.get());
        drainRetries();
        assertEquals(0, sentCommands.size());
        assertTrue(MaxState.storageError);
        assertTrue(MaxSender.statusText(context).contains("Ошибка записи"));
        assertFalse(MaxSender.statusText(context).contains("6.1"));
    }

    @Test
    public void targetWriteFails_TargetNotUsed() {
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        env.failMode = 1;
        assertFalse(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        drainRetries();
        // Неподтверждённая цель не используется: ни команда, ни адресат.
        assertNull(MaxChannel.getTarget(context));
        MaxSender.onReading(context, "6.2", clock.get());
        drainRetries();
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void dirtyCacheNotConfirmedAndNotBypassedByNewHandle() {
        selectPapa();
        env.failMode = 1;
        MaxSender.onReading(context, "6.2", clock.get());
        drainRetries();
        assertEquals(0, sentCommands.size());
        // Кеш «запачкан» неподтверждённым pending — диск его не содержит.
        assertTrue(env.cache.containsKey("pending_text"));
        assertFalse(env.disk.containsKey("pending_text"));

        // Новый handle ТОГО ЖЕ процесса — тот же кеш, запрет не обходит:
        env.failMode = 0;
        MaxChannel.storeFactoryForTest = c -> new FakeStore(env);
        MaxChannel.resetForTest(); // пересборка снимка — процесс тот же, storageError жив
        assertNull("неподтверждённые данные не считаются состоянием",
                MaxChannel.getPending(context));
        assertTrue(MaxState.storageError);
        MaxSender.onButtonCaptured(context);
        assertEquals(0, sentCommands.size());

        // «Новый процесс» — только диск: pending неподтверждённого нет.
        restartFromDisk();
        assertNull(MaxChannel.getPending(context));
        assertNotNull(MaxChannel.getTarget(context)); // подтверждённая цель сохранена
    }

    @Test
    public void exceptionAfterCacheMutation_NoCommand() {
        selectPapa();
        env.failMode = 2; // исключение после изменения кеша
        MaxSender.onReading(context, "6.3", clock.get());
        drainRetries();
        assertEquals(0, sentCommands.size());
        assertTrue(MaxState.storageError);
        assertFalse(env.disk.containsKey("pending_text"));
    }

    // --- устаревшие повторы записи (А–Г) ----------------------------------------

    @Test
    public void staleTargetRetryKeepsNewerSelection() {
        // А: выбор «Папы» отказал → повтор отложен → «Мама» подтверждена →
        // повтор «Папы» устарел и цель не меняет.
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        MaxSender.Candidate papa = null;
        for (MaxSender.Candidate c : MaxSender.candidates()) {
            if (c.chatName().equals("Папа")) papa = c;
        }
        assertNotNull(papa);

        env.failMode = 1;
        assertFalse(MaxSender.selectCandidate(context, papa));
        env.failMode = 0;
        assertEquals("повтор записи цели отложен", 1, retryQueue.size());

        selectMama();
        long genMama = MaxChannel.getTarget(context).generation;
        PendingIntent mamaPi = MaxState.targetButton.pendingIntent;

        retryQueue.poll().run(); // устаревший повтор выбора «Папы»
        assertEquals("Мама", MaxChannel.getTarget(context).chatName);
        assertEquals(genMama, MaxChannel.getTarget(context).generation);
        assertSame("кнопка — выбранной «Мамы»", mamaPi,
                MaxState.targetButton.pendingIntent);
        assertEquals("Мама", MaxReplyBackup.restore(context).chatName);
    }

    @Test
    public void stalePendingRetryKeepsNewerPending() {
        // Б: запись старого показания отказала → новое подтверждено →
        // повтор старого не заменяет новое.
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context); // команды не будет — проверяем только pending

        env.failMode = 1;
        MaxSender.onReading(context, "6.1", clock.get());
        env.failMode = 0;
        assertEquals(1, retryQueue.size());

        clock.addAndGet(60_000);
        MaxSender.onReading(context, "6.2", clock.get());
        assertEquals("6.2", MaxChannel.getPending(context).text);

        retryQueue.poll().run(); // устаревший повтор записи «6.1»
        assertEquals("новое показание не заменено старым",
                "6.2", MaxChannel.getPending(context).text);
    }

    @Test
    public void staleClearRetryKeepsNewPending() {
        // В: удаление старого pending отказало → появилось новое →
        // повтор удаления новое не трогает.
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.3", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        env.failMode = 1;
        clock.addAndGet(MaxChannel.PENDING_MAX_AGE_MS + 60_000);
        MaxSender.onButtonCaptured(context); // очистка просроченного — отказ
        env.failMode = 0;
        assertEquals(1, retryQueue.size());
        assertNotNull("pending остался — удаление не подтверждено",
                MaxChannel.getPending(context));

        clock.addAndGet(60_000);
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals("6.4", MaxChannel.getPending(context).text);

        retryQueue.poll().run(); // устаревший повтор удаления
        assertEquals("новое pending не удалено старым повтором",
                "6.4", MaxChannel.getPending(context).text);
    }

    @Test
    public void staleResultRetryDoesNotTouchNextAttempt() {
        // Г: итог старой попытки не записался → появилась следующая попытка →
        // старый повтор не меняет её состояние и не возвращает старый watermark.
        selectPapa();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
            env.failMode = 1; // запись итога первой попытки откажет
        };
        long ts1 = clock.get();
        MaxSender.onReading(context, "6.5", ts1);
        assertEquals(1, sentCommands.size());
        env.failMode = 0;
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
        };
        assertEquals(1, retryQueue.size());
        long attempt1 = MaxChannel.getAttempt(context).id;

        // Следующая попытка — обычный путь: первая же подтверждённая запись
        // (pending) снимает storageError.
        clock.addAndGet(60_000);
        long ts2 = clock.get();
        MaxSender.onReading(context, "6.6", ts2);
        assertEquals(2, sentCommands.size());
        long attempt2 = MaxChannel.getAttempt(context).id;
        assertTrue(attempt2 > attempt1);
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getAttempt(context).state);

        retryQueue.poll().run(); // устаревший повтор итога первой попытки
        assertEquals("состояние следующей попытки не изменено",
                attempt2, MaxChannel.getAttempt(context).id);
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getAttempt(context).state);
        assertEquals("watermark остался от нового показания",
                ts2, MaxChannel.getLastHandoffReadingTs(context));
    }

    @Test
    public void retryDelaysIncrease() {
        selectPapa();
        env.failMode = 1;
        MaxSender.onReading(context, "6.7", clock.get());
        // Первая неудача — повтор с базовой задержкой; запускаем его — он
        // тоже падает и планирует следующий с увеличенной паузой.
        retryQueue.poll().run();
        assertEquals(2, retryDelays.size());
        assertEquals(MaxChannel.RETRY_BASE_MS, (long) retryDelays.get(0));
        assertEquals(2 * MaxChannel.RETRY_BASE_MS, (long) retryDelays.get(1));
        // Повторы реально идут через commit — счётчик это подтверждает.
        assertTrue(env.commits.get() >= 2);
        env.failMode = 0;
        drainRetries();
    }

    // --- тест не отменяет запрет повтора сахара ----------------------------------

    @Test
    public void unknownSugarThenTestThenNewButton_TNotResent() {
        selectPapa();
        // Резерв прошёл, граница команды бросила исключение — исход неизвестен.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
            throw new RuntimeException("исход неизвестен");
        };
        long ts = clock.get();
        MaxSender.onReading(context, "6.6", ts);
        assertEquals(1, sentCommands.size());
        assertEquals(MaxChannel.Attempt.STATE_UNKNOWN,
                MaxChannel.getAttempt(context).state);
        assertNotNull(MaxChannel.getPending(context));

        // Тестовое сообщение успешно — попытка показания не перезаписана.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
        };
        assertTrue(MaxSender.sendTest(context).contains("передано"));
        assertEquals(2, sentCommands.size());
        assertEquals(MaxChannel.Attempt.STATE_UNKNOWN,
                MaxChannel.getAttempt(context).state);
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getTestAttempt(context).state);

        // Новая кнопка выбранного чата — старый сахар повторно не уходит.
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(2, sentCommands.size());
        assertFalse("текст 6.6 не отправлен повторно",
                sentTexts().subList(1, sentTexts().size()).contains("6.6"));
        assertTrue(MaxSender.statusText(context).contains("неизвестен"));

        // Следующее НОВОЕ показание идёт обычным путём — один раз.
        clock.addAndGet(60_000);
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(3, sentCommands.size());
        assertEquals("6.7", fillInText(sentFillIns.get(2)));
    }

    @Test
    public void reservedAfterRestartThenTestThenNewButton() {
        // RESERVED на диске после «перезапуска» + успешный тест + новая кнопка.
        plantReservedAttempt("6.8");
        restartFromDisk();
        assertNotNull(MaxChannel.getPending(context));

        assertTrue(MaxSender.sendTest(context).contains("передано"));
        assertEquals(1, sentCommands.size());
        assertTrue(fillInText(sentFillIns.get(0)).startsWith("Тест:"));

        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals("сахар с неизвестным исходом не повторён",
                1, sentCommands.size());
        assertFalse(sentTexts().contains("6.8"));
    }

    @Test
    public void testThenRestartKeepsReservedSugarBlocked() {
        // RESERVED → тест успешен → «перезапуск» → новая кнопка:
        // независимое доказательство запрета переживает восстановление.
        plantReservedAttempt("6.9");
        restartFromDisk();
        assertTrue(MaxSender.sendTest(context).contains("передано"));
        assertEquals(1, sentCommands.size());
        restartFromDisk();
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertFalse(sentTexts().contains("6.9"));
        assertEquals(MaxChannel.Attempt.STATE_RESERVED,
                MaxChannel.getAttempt(context).state);
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getTestAttempt(context).state);
    }

    @Test
    public void failedTestKeepsCoverOnSugar() {
        selectPapa();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
            throw new RuntimeException("исход неизвестен");
        };
        MaxSender.onReading(context, "7.0", clock.get());
        assertEquals(MaxChannel.Attempt.STATE_UNKNOWN,
                MaxChannel.getAttempt(context).state);

        // Неуспешный тест — MAX отменил кнопку.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            throw new PendingIntent.CanceledException();
        };
        String res = MaxSender.sendTest(context);
        assertFalse(res.contains("передано"));

        // Живая кнопка и новое уведомление — сахар всё ещё покрыт UNKNOWN.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
        };
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals("повтор сахара после неуспешного теста запрещён",
                1, sentCommands.size());
        assertFalse(sentTexts().subList(1, sentTexts().size()).contains("7.0"));
    }

    private void plantReservedAttempt(String text) {
        selectPapa();
        MaxStore s = MaxChannel.storeFactoryForTest.apply(context);
        long gen = MaxChannel.getTarget(context).generation;
        s.edit().putLong("attempt_id", 42)
                .putString("attempt_kind", MaxChannel.Attempt.KIND_READING)
                .putLong("attempt_gen", gen)
                .putString("attempt_chat", "Папа")
                .putString("attempt_channel", "ru.oneme.app.dialogs")
                .putInt("attempt_notif_id", 378)
                .putLong("attempt_reading_ts", clock.get())
                .putInt("attempt_state", MaxChannel.Attempt.STATE_RESERVED)
                .putString("pending_text", text)
                .putLong("pending_reading_ts", clock.get())
                .putLong("pending_gen", gen)
                .commit();
    }

    // --- конфликт на настоящем рабочем исполнителе -------------------------------

    @Test
    public void conflictOnRealWorkerBlocksCommand() throws Exception {
        // Настоящая производственная очередь: commit резерва удержан,
        // настоящий listener принимает конфликт и возвращается сразу —
        // команда не успевает выполниться до обработки конфликта.
        ExecutorService real = Executors.newSingleThreadExecutor();
        MaxSender.workerForTest = real;
        try {
            Intent op = new Intent("com.example.xdrip2vk.test.RW_OP");
            PendingIntent piA = PendingIntent.getBroadcast(context, 91, op,
                    PendingIntent.FLAG_IMMUTABLE);
            PendingIntent piB = equalWrapper(piA, op);

            listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, piA));
            real.submit(() -> { }).get(10, TimeUnit.SECONDS); // кандидат обработан
            assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));

            // Удерживаем commit резерва (pending — предыдущий commit).
            env.holdOnCommit = env.commits.get() + 2;
            env.holdStart = new CountDownLatch(1);
            env.holdRelease = new CountDownLatch(1);
            MaxSender.onReading(context, "7.1", clock.get());
            assertTrue("рабочий поток удержан в commit резерва",
                    env.holdStart.await(10, TimeUnit.SECONDS));

            CountDownLatch listenerReturned = new CountDownLatch(1);
            new Thread(() -> {
                listener.onNotificationPosted(
                        maxSbn("Мама", "ru.oneme.app.dialogs", 555, piB));
                listenerReturned.countDown();
            }).start();
            assertTrue("listener вернулся без ожидания",
                    listenerReturned.await(10, TimeUnit.SECONDS));

            env.holdRelease.countDown();
            // Ждём полной обработки очереди worker-а.
            real.submit(() -> { }).get(10, TimeUnit.SECONDS);
            real.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertEquals("команда не вызвана — конфликт обработан до неё",
                    0, sentCommands.size());
            assertEquals(MaxChannel.BLOCKED_CONFLICT,
                    MaxChannel.getBlockedReason(context));
            assertNotNull("ожидающий сахар сохранён", MaxChannel.getPending(context));
        } finally {
            real.shutdownNow();
            MaxSender.workerForTest = Runnable::run;
        }
    }

    @Test
    public void listenerDoesNotWaitForHeldCommit() throws Exception {
        selectPapa();
        ExecutorService real = Executors.newSingleThreadExecutor();
        MaxSender.workerForTest = real;
        try {
            env.holdOnCommit = env.commits.get() + 1; // первый commit — запись pending
            env.holdStart = new CountDownLatch(1);
            env.holdRelease = new CountDownLatch(1);

            MaxSender.onReading(context, "7.3", clock.get());
            assertTrue("рабочий поток вошёл в удержанную запись",
                    env.holdStart.await(10, TimeUnit.SECONDS));

            CountDownLatch listenerReturned = new CountDownLatch(1);
            new Thread(() -> {
                listener.onNotificationPosted(
                        maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
                listenerReturned.countDown();
            }).start();
            assertTrue("поток слушателя не ждёт удержанную запись",
                    listenerReturned.await(10, TimeUnit.SECONDS));

            env.holdRelease.countDown();
            real.submit(() -> { }).get(10, TimeUnit.SECONDS);
            real.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(1, sentCommands.size());
        } finally {
            real.shutdownNow();
            MaxSender.workerForTest = Runnable::run;
        }
    }

    // --- восстановление и неопределённый исход -----------------------------------

    @Test
    public void restoredPendingKeepsTargetTextAndTtl() {
        selectPapa();
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.3", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        restartFromDisk();
        MaxChannel.Pending p = MaxChannel.getPending(context);
        assertNotNull(p);
        assertEquals("6.3", p.text);
        assertEquals(MaxChannel.getTarget(context).generation, p.targetGeneration);

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
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void resultWriteFailsAfterSend_NoRetryOfCommand() {
        selectPapa();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
            env.failMode = 1;
        };
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals(1, sentCommands.size());
        drainRetries(); // повторы итога тоже отказали — RESERVED остаётся на диске
        env.failMode = 0;

        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals(1, sentCommands.size());
        restartFromDisk();
        MaxSender.onButtonCaptured(context);
        assertEquals(1, sentCommands.size());
        assertTrue(MaxState.outcomeUnknown);
        assertTrue(MaxSender.statusText(context).contains("неизвестен"));

        clock.addAndGet(60_000);
        MaxSender.onReading(context, "6.5", clock.get());
        assertEquals(2, sentCommands.size());
        assertEquals("6.5", fillInText(sentFillIns.get(1)));
    }

    @Test
    public void reservedAttemptNotRetriedAfterRestart() {
        selectPapa();
        long ts = clock.get();
        MaxSender.onReading(context, "6.6", ts);
        assertEquals(1, sentCommands.size());

        MaxStore s = MaxChannel.storeFactoryForTest.apply(context);
        s.edit().putInt("attempt_state", MaxChannel.Attempt.STATE_RESERVED)
                .putString("pending_text", "6.6")
                .putLong("pending_reading_ts", ts)
                .putLong("pending_gen", MaxChannel.getTarget(context).generation)
                .commit();
        restartFromDisk();
        assertNotNull(MaxChannel.getPending(context));
        assertTrue(MaxState.outcomeUnknown);

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
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(1, sentCommands.size());
        assertEquals(MaxChannel.Attempt.STATE_HANDED,
                MaxChannel.getAttempt(context).state);
    }

    @Test
    public void expiredDuringCommit_NoCommand() {
        selectPapa();
        hookOnReserveCommit(() -> clock.addAndGet(MaxChannel.PENDING_MAX_AGE_MS + 60_000));
        MaxSender.onReading(context, "6.8", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void stopDuringCommit_NoCommand() {
        selectPapa();
        hookOnReserveCommit(() ->
                prefs.edit().putBoolean("transmission_enabled", false).commit());
        MaxSender.onReading(context, "6.9", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void conflictDuringCommit_NoCommand() {
        selectPapa();
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

        selectMama();
        assertNull(MaxChannel.getPending(context));
        long genMama = MaxChannel.getTarget(context).generation;

        restartFromDisk();
        assertNull(MaxChannel.getPending(context));
        assertEquals(genMama, MaxChannel.getTarget(context).generation);
        MaxSender.onButtonCaptured(context);
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void newerPendingSurvivesOldAttemptFinish() {
        selectPapa();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
            MaxStore s = MaxChannel.storeFactoryForTest.apply(context);
            s.edit().putString("pending_text", "7.2")
                    .putLong("pending_reading_ts", clock.get() + 60_000)
                    .putLong("pending_gen", MaxChannel.getTarget(context).generation)
                    .commit();
        };
        MaxSender.onReading(context, "7.1", clock.get());
        assertEquals(1, sentCommands.size());
        restartFromDisk();
        MaxChannel.Pending p = MaxChannel.getPending(context);
        assertNotNull(p);
        assertEquals("7.2", p.text);
    }

    @Test
    public void retryOfUnconfirmedWriteReallyCommits() {
        selectPapa();
        int before = env.commits.get();
        env.failMode = 1;
        MaxSender.onReading(context, "7.4", clock.get());
        assertEquals(0, sentCommands.size());
        drainRetries();
        assertTrue(env.commits.get() - before >= MaxChannel.MAX_WRITE_ATTEMPTS);
        env.failMode = 0;
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
