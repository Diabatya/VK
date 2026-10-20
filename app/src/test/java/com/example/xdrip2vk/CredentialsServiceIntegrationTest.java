package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.SendResult;
import com.example.xdrip2vk.core.SgvReading;
import com.example.xdrip2vk.store.AndroidCredentialBackend;
import com.example.xdrip2vk.store.CredentialBackend;
import com.example.xdrip2vk.store.CredentialStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Интеграционные проверки задания 2Г.4: настоящий сервис + настоящий
 * фасад Credentials + настоящий AndroidCredentialBackend (граница
 * открытия encrypted — подменённый SharedPreferences; сеть подменена
 * sender/identity overrides). Все значения синтетические.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CredentialsServiceIntegrationTest {

    private Context context;
    private SharedPreferences prefs;
    private final List<String> sends = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        prefs.edit().putBoolean(
                VkSenderService.PREF_TRANSMISSION_ENABLED, true).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        sends.clear();
        VkSenderService.senderOverride = (peer, text, rid) -> {
            sends.add(peer + "|" + text);
            return SendResult.SENT;
        };
        VkSenderService.identityVerifierOverride = (token) -> "user:1";
        VkSenderService.bgDataOverride = java.util.Collections::emptyList;
        VkSenderService.treatmentsOverride = java.util.Collections::emptyList;
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        VkSenderService.bgDataOverride = null;
        VkSenderService.treatmentsOverride = null;
        VkSenderService.prefsOverride = null;
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class)
                .create().get();
        Shadows.shadowOf(s.workerLooperForTest()).idle();
        return s;
    }

    private void idle(VkSenderService s) {
        Shadows.shadowOf(s.workerLooperForTest()).idle();
    }

    private Intent autoUpdate(double bg, long ts) {
        Intent i = new Intent(context, VkSenderService.class);
        i.putExtra("auto_update", true);
        i.putExtra("bg_value", bg);
        i.putExtra("timestamp", ts);
        i.putExtra("direction", "Flat");
        return i;
    }

    /** Холодный старт: реквизиты только в encrypted — обычная отправка. */
    @Test
    public void coldStartEncryptedOnlySends() {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);

        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("777|"));
        s.onDestroy();
    }

    /**
     * Холодный старт: реквизиты только в legacy-plain — подтверждённая
     * миграция в encrypted, источник очищен, отправка работает.
     */
    @Test
    public void coldStartPlainOnlyMigratesThenSends() throws Exception {
        SharedPreferences plain = context.getSharedPreferences(
                "secure_tokens_plain", Context.MODE_PRIVATE);
        plain.edit()
                .putString("vk_token", "SYNTH_TOKEN_P")
                .putLong("vk_chat_id", 555L)
                .commit();

        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("555|"));
        // Миграция подтверждена: назначение заполнено, источник очищен.
        assertEquals("SYNTH_TOKEN_P", CredHelper.getToken(context));
        assertFalse(plain.contains("vk_token"));
        assertEquals(CredentialBackend.STATE_ACTIVE,
                CredHelper.enc(context).getString("cred_service_state", ""));
        s.onDestroy();
    }

    /**
     * Конфликт (разные токены в enc и default): ноль отправок, данные
     * обоих источников сохранены.
     */
    @Test
    public void conflictZeroSendsAndDataPreserved() {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        prefs.edit().putString("vk_token", "SYNTH_TOKEN_B").commit();

        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertTrue("конфликт — ни одной отправки", sends.isEmpty());
        assertEquals("SYNTH_TOKEN_A", CredHelper.getToken(context));
        assertEquals("SYNTH_TOKEN_B", prefs.getString("vk_token", ""));
        s.onDestroy();
    }

    /**
     * Смена владельца при отказе записи сброса чатов: унаследованные
     * адресаты не используются — ни одной отправки.
     */
    @Test
    public void failedChatResetDoesNotPermitInheritedPeers() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        // Новый токен чужого владельца; commit сброса чатов отказывает.
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                (token) -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        SharedPreferences enc = CredHelper.enc(context);
        CredentialBackend wrapped = new CredentialBackend() {
            final CredentialBackend d =
                    new AndroidCredentialBackend(context, ctx -> enc);
            @Override public Object storageId() { return d.storageId(); }
            @Override public com.example.xdrip2vk.core.CredentialAudit.Source
                    snapshot(com.example.xdrip2vk.core.CredentialAudit.Role role) {
                return d.snapshot(role);
            }
            @Override public ServiceState serviceState(
                    com.example.xdrip2vk.core.CredentialAudit.Role role) {
                return d.serviceState(role);
            }
            @Override public boolean putCredentials(
                    String t, long c, long a, String st) {
                return false; // commit сброса отклонён
            }
            @Override public boolean deleteCredentials() {
                return d.deleteCredentials();
            }
            @Override public boolean removeCredentials(
                    com.example.xdrip2vk.core.CredentialAudit.Role role) {
                return d.removeCredentials(role);
            }
        };
        CredentialStore.resetCoordinatorForTest(wrapped);
        Credentials.resetForTest();
        Credentials.backendForTest = wrapped;
        Credentials.permitMainBlockingForTest = true;

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);

        assertTrue("сброс не подтверждён — унаследованные адресаты не использованы",
                sends.isEmpty());
        s2.onDestroy();
    }

    /**
     * Постоянный Stop: подтверждённое сохранение токена и его уведомление
     * сервис не запускают.
     */
    @Test
    public void stopThenTokenSaveDoesNotStartService() throws Exception {
        prefs.edit().putBoolean(
                VkSenderService.PREF_TRANSMISSION_ENABLED, false).commit();
        VkSenderService.resetTransmissionFlagsForTest();

        Future<CredentialStore.Resolution> f =
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_NEW");
        assertEquals(CredentialStore.Status.READY,
                f.get(10, TimeUnit.SECONDS).status);

        // Тот же путь, что у SettingsActivity после подтверждённой записи.
        VkSenderService.notifyTokenChanged(context);

        assertNull("сервис не запущен после Stop",
                Shadows.shadowOf((Application) context).getNextStartedService());
    }

    /**
     * Недоступное хранилище: отсутствие загруженного снимка не трактуется
     * как «новая ненастроенная установка» — ключ выбора не записывается.
     */
    @Test
    public void unreadableSnapshotDoesNotWriteTransmissionFalse() {
        prefs.edit().remove(VkSenderService.PREF_TRANSMISSION_ENABLED).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        CredentialBackend unavailable = new AndroidCredentialBackend(context,
                ctx -> { throw new SecurityException("synthetic keystore down"); });
        CredentialStore.resetCoordinatorForTest(unavailable);
        Credentials.resetForTest();
        Credentials.backendForTest = unavailable;
        Credentials.permitMainBlockingForTest = true;

        assertFalse(VkSenderService.isTransmissionAllowed(context));
        VkSenderService.awaitSwitchIdleForTest();

        assertFalse("недостоверный снимок не пишет выбор пользователя",
                prefs.contains(VkSenderService.PREF_TRANSMISSION_ENABLED));
    }

    /**
     * READY-снимок с реквизитами при первом обращении инициализирует
     * миграцию выбора как «передача разрешена» (совместимость установок).
     */
    @Test
    public void legacyConfiguredCredsMigrateTransmissionOn() {
        prefs.edit().remove(VkSenderService.PREF_TRANSMISSION_ENABLED).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);

        assertFalse(VkSenderService.isTransmissionAllowed(context));
        VkSenderService.awaitSwitchIdleForTest();

        assertTrue("прежняя настроенная установка → передача разрешена",
                prefs.getBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false));
        assertTrue(VkSenderService.isTransmissionAllowed(context));
    }

    // ==================== 2Г.4Б: завершение смены владельца ====================

    private static final String LAST_SENDER = "last_active_sender";
    private static final String CONFIRMED_SENDER = "confirmed_sender";
    private static final String CONFIRMED_SENDER_FP = "confirmed_sender_fp";

    /** Backend, чей commit записи отказывает, пока флаг не снят тестом. */
    private void installFailingPutBackend(
            java.util.concurrent.atomic.AtomicBoolean failing) {
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        hb.putOverride = (t, c, a, st) ->
                !failing.get() && hb.d.putCredentials(t, c, a, st);
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;
    }

    private void bareDrain(VkSenderService s) {
        s.onStartCommand(new Intent(), 0, 0);
        idle(s);
    }

    /**
     * Точное воспроизведение 2Г.4Б: commit=false на сбросе чатов →
     * last_active_sender остаётся прежним; восстановление записи
     * завершает переход; явный выбор новой беседы; новое показание
     * доставляется новым аккаунтом ровно один раз.
     */
    @Test
    public void failedResetThenRecoveryCompletesTransition() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        s.onDestroy();

        // Токен B — другой владелец; commit сброса отказывает.
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        java.util.concurrent.atomic.AtomicBoolean failing =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue("сброс не подтверждён — отправок нет", sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        assertEquals("user:2", prefs.getString(CONFIRMED_SENDER, ""));

        // Восстановление записи: resolve завершает ожидающий сброс.
        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals("сброс применён на диске", 0,
                CredHelper.getChatId(context));

        // Обход без новых данных фиксирует завершение перехода; запрет
        // публикации снимается асинхронно — ждём очередь и повторяем обход.
        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));

        // Явный выбор новой беседы под версией B.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(888,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // Новое свежее показание доставлено новым аккаунтом ровно один раз;
        // старое сообщение (enqueued до перехода) аккаунтом B не ушло.
        s2.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s2);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));

        // Повторный обход не стирает выбранные новые чаты.
        bareDrain(s2);
        assertEquals(888, CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        s2.onDestroy();
    }

    /**
     * Новый экземпляр сервиса продолжает незавершённый переход: pending
     * операция в общем координаторе переживает экземпляр сервиса.
     * Это тот же процесс — координатор не сбрасывается (настоящий новый
     * процесс моделируется CredHelper.recreate в других тестах).
     */
    @Test
    public void newServiceInstanceCompletesPendingTransition() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        java.util.concurrent.atomic.AtomicBoolean failing =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        s2.onDestroy();
        failing.set(false);

        // Тот же процесс: общий координатор и его pending-операция живы,
        // новый экземпляр сервиса подхватывает завершение перехода.
        VkSenderService s3 = startService();
        bareDrain(s3);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s3);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals(0, CredHelper.getChatId(context));
        s3.onDestroy();
    }

    /**
     * Граница: commit сброса прошёл, фиксация завершения потеряна (сбой
     * между записью чатов и LAST_SENDER). Восстановление завершает
     * переход без стирания и без наследования старых адресатов.
     */
    @Test
    public void committedResetWithoutFinalizationRecoversSafely() throws Exception {
        // На диске — состояние после подтверждённого сброса: B + 0.
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        prefs.edit()
                .putString(CONFIRMED_SENDER, "user:2")
                .putString(CONFIRMED_SENDER_FP,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .putString(LAST_SENDER, "user:1") // фиксация потеряна
                .commit();
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";

        sends.clear();
        VkSenderService s = startService();
        bareDrain(s);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s);

        assertEquals("переход доведён до конца без повторного стирания",
                "user:2", prefs.getString(LAST_SENDER, ""));
        // Явный выбор новой беседы и доставка новым аккаунтом.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(888,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        s.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));
        // Повторный обход: выбранные чаты не стёрты.
        bareDrain(s);
        assertEquals(888, CredHelper.getChatId(context));
        s.onDestroy();
    }

    /**
     * Токен заменён во время восстановления перехода: устаревший переход
     * версии B не применяется к версии C — переход заново выполняется
     * под подтверждённым владельцем C.
     */
    @Test
    public void tokenReplacedDuringRecoveryKeepsNewVersion() throws Exception {
        // Незавершённый переход для B: личность подтверждена, сброс нет.
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        CredHelper.setChatId(context, 777);
        prefs.edit()
                .putString(CONFIRMED_SENDER, "user:2")
                .putString(CONFIRMED_SENDER_FP,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .putString(LAST_SENDER, "user:1")
                .commit();
        // Токен заменён до того, как переход восстановился.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setTokenAsync("SYNTH_TOKEN_C").get(10, TimeUnit.SECONDS).status);
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_C") ? "user:3" : "user:1";

        sends.clear();
        VkSenderService s = startService();
        bareDrain(s);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s);

        // Переход выполнен под C: унаследованные адресаты B сброшены.
        assertEquals("user:3", prefs.getString(LAST_SENDER, ""));
        assertEquals(0, CredHelper.getChatId(context));
        assertEquals("SYNTH_TOKEN_C", CredHelper.getToken(context));
        s.onDestroy();
    }

    /**
     * Продолжающийся отказ записи: запрет сохраняется, последующие обходы
     * не создают частого цикла и не открывают унаследованные адресаты.
     */
    @Test
    public void persistentResetFailureBlocksWithoutLoop() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        // commit отказывает всегда.
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        hb.putOverride = (t, c, a, st) -> false;
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        bareDrain(s2); // повторный обход — запрет сохраняется без цикла
        bareDrain(s2);

        assertTrue("отказ записи — отправок нет", sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        assertEquals("данные не затёрты", "SYNTH_TOKEN_B",
                CredHelper.getToken(context));
        assertEquals(777, CredHelper.getChatId(context));
        s2.onDestroy();
    }

    // ==================== 2Г.4Б.1: новый выбор после подтверждённого сброса ====================

    /**
     * Точное воспроизведение 2Г.4Б.1: после отказа первого commit сброса
     * запись восстановлена, resolveAsync завершает pending-сброс, затем
     * БЕЗ промежуточного обхода сервиса выбрана новая беседа 888.
     * Следующий обход обязан завершить переход, НЕ стирая 888; новое
     * показание доставлено в 888 ровно один раз.
     */
    @Test
    public void newSelectionAfterConfirmedResetNotErased() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue("сброс не подтверждён — отправок нет", sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        assertEquals("user:2", prefs.getString(CONFIRMED_SENDER, ""));
        assertTrue("маркер перехода записан до первого сброса",
                prefs.contains("owner_transition"));

        // Восстановление записи: resolve завершает ожидающий сброс.
        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(0, CredHelper.getChatId(context));

        // Явный выбор новой беседы БЕЗ промежуточного обхода сервиса.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(888,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // Следующий обход с cached verified: завершение перехода не
        // стирает 888, новое свежее показание уходит в него один раз.
        s2.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("выбранный чат сохранён", 888,
                CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));

        // Повторные обходы не стирают выбранные чаты и не шлют повторно.
        bareDrain(s2);
        bareDrain(s2);
        assertEquals(888, CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        s2.onDestroy();
    }

    /**
     * То же для тревожного адресата с отрицательным peer_id: выбор -999
     * после подтверждённого сброса не стирается завершением перехода.
     */
    @Test
    public void alertSelectionAfterConfirmedResetNotErased() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        CredHelper.setAlertChatId(context, -555);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));

        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(0, CredHelper.getAlertChatId(context));

        // Новый выбор тревожного адресата без обхода сервиса.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setAlertChatIdAsync(-999,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("тревожный адресат сохранён", -999,
                CredHelper.getAlertChatId(context));
        s2.onDestroy();
    }

    /**
     * commit=false с изменённым кешем: snapshot читает новые значения,
     * но commit не подтверждён. Восстановление и новый выбор не теряются.
     */
    @Test
    public void dirtyCacheCommitFalseThenSelectionSurvives() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        AtomicBoolean failing = new AtomicBoolean(true);
        hb.putOverride = (t, c, a, st) -> {
            if (failing.get()) {
                // Кеш изменён, commit()=false: грязный кеш не подтверждён.
                // Служебная строка — композит: состояние и мета полей
                // пишутся в разные ключи, как у настоящего backend.
                String fm = CredentialBackend.fieldMetaPart(st);
                SharedPreferences.Editor e = CredHelper.enc(context).edit()
                        .putString("vk_token", t)
                        .putLong("vk_chat_id", c)
                        .putLong("alert_chat_id", a)
                        .putString("cred_service_state",
                                CredentialBackend.serviceStatePart(st));
                if (fm.isEmpty()) {
                    e.remove("cred_field_meta");
                } else {
                    e.putString("cred_field_meta", fm);
                }
                e.apply();
                return false;
            }
            return hb.d.putCredentials(t, c, a, st);
        };
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));

        // Восстановление: pending повторяет реальный commit.
        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(888,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // Завершение перехода до постановки нового сообщения: peerOwner
        // нового сообщения — уже user:2.
        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));

        s2.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals(888, CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));
        s2.onDestroy();
    }

    /**
     * «Новый процесс» (CredHelper.recreate — координатор и pending
     * потеряны) после подтверждённого сброса и нового выбора, но до
     * завершения перехода: адресаты сохранены, переход завершается.
     */
    @Test
    public void recreatedServiceKeepsSelectionAfterConfirmedReset() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        s2.onDestroy();

        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(888,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // Настоящий «новый процесс»: координатор и pending сброшены,
        // файлы и маркер перехода сохранены.
        CredHelper.recreate(context);
        VkSenderService s3 = startService();
        bareDrain(s3);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s3);

        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("выбор пережил пересоздание сервиса", 888,
                CredHelper.getChatId(context));
        s3.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s3);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));
        s3.onDestroy();
    }

    /**
     * «Новый процесс» с НЕподтверждённым сбросом (pending потерян, диск
     * хранит старые адресаты): условный сброс по маркеру реально очищает
     * унаследованные поля и завершает переход.
     */
    @Test
    public void inheritedChatsClearedAfterRecreation() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        CredHelper.setAlertChatId(context, -555);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        s2.onDestroy();

        // Новый процесс без восстановления записи: pending потерян, диск
        // хранит унаследованные 777/-555, маркер перехода сохранён.
        // Подменяемый backend заменяется на обычный — запись работает.
        CredHelper.recreate(context);
        VkSenderService s3 = startService();
        bareDrain(s3);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s3);

        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("унаследованный чат сброшен", 0,
                CredHelper.getChatId(context));
        assertEquals("унаследованный alert сброшен", 0,
                CredHelper.getAlertChatId(context));
        assertTrue(sends.isEmpty());
        s3.onDestroy();
    }

    /**
     * Реальная замена токена ВО ВРЕМЯ удержанной операции восстановления:
     * latch внутри commit сброса версии B, настоящий setTokenAsync(C)
     * ждёт в очереди координатора, latch освобождается. Устаревший
     * переход B не фиксирует LAST_SENDER и не затрагивает версию C;
     * переход выполняется заново под подтверждённым владельцем C.
     */
    @Test
    public void tokenReplacedWhileResetHeldKeepsNewVersion() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        // Для C личность подтверждается только после allowC: иначе drain,
        // продолжившийся после освобождения put, сам бы завершил переход
        // до наших проверок.
        final AtomicBoolean allowC = new AtomicBoolean(false);
        VkSenderService.identityVerifierOverride = token ->
                token.equals("SYNTH_TOKEN_B") ? "user:2"
                        : (token.equals("SYNTH_TOKEN_C") && allowC.get())
                                ? "user:3" : null;

        final CountDownLatch putEntered = new CountDownLatch(1);
        final CountDownLatch putRelease = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean(true);
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        final AtomicBoolean heldUntilRelease = new AtomicBoolean(false);
        hb.putOverride = (t, c, a, st) -> {
            if (once.getAndSet(false)) {
                putEntered.countDown();
                try {
                    heldUntilRelease.set(
                            putRelease.await(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return hb.d.putCredentials(t, c, a, st);
        };
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        // Drain на отдельном потоке: операция сброса встанет внутри put.
        Thread pump = new Thread(
                () -> Shadows.shadowOf(s2.workerLooperForTest()).idle());
        pump.start();
        assertTrue("операция сброса удержана внутри commit",
                putEntered.await(10, TimeUnit.SECONDS));

        // Замена токена во время удержанной операции: команда ставится
        // за удержанной операцией в том же координаторе.
        Future<CredentialStore.Resolution> fC =
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_C");
        putRelease.countDown();
        assertEquals(CredentialStore.Status.READY,
                fC.get(10, TimeUnit.SECONDS).status);
        pump.join(15_000);
        Credentials.get(context).awaitPublishIdleForTest();
        assertTrue("операция была удержана до замены токена",
                heldUntilRelease.get());

        // Устаревший переход B не применён к новой версии.
        assertEquals("устаревший переход не зафиксировал LAST_SENDER",
                "user:1", prefs.getString(LAST_SENDER, ""));
        assertEquals("SYNTH_TOKEN_C", CredHelper.getToken(context));
        assertTrue("чат 777 не ушёл устаревшему переходу",
                sends.isEmpty());

        // Переход заново под подтверждённым владельцем C.
        allowC.set(true);
        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);
        assertEquals("user:3", prefs.getString(LAST_SENDER, ""));
        assertEquals(0, CredHelper.getChatId(context));
        assertEquals("SYNTH_TOKEN_C", CredHelper.getToken(context));
        s2.onDestroy();
    }

    /**
     * После завершения перехода повторные обходы не выполняют ни одной
     * записи сброса: ни одного putCredentials не вызывается.
     */
    @Test
    public void completedTransitionIssuesNoResetWrites() throws Exception {
        // Переход уже завершён: B/user:2, чат 888 выбран под этой версией.
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        CredHelper.setChatId(context, 888);
        prefs.edit()
                .putString(CONFIRMED_SENDER, "user:2")
                .putString(CONFIRMED_SENDER_FP,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .putString(LAST_SENDER, "user:2")
                .commit();
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        final AtomicInteger puts = new AtomicInteger();
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        hb.putOverride = (t, c, a, st) -> {
            puts.incrementAndGet();
            return hb.d.putCredentials(t, c, a, st);
        };
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;

        sends.clear();
        VkSenderService s = startService();
        bareDrain(s);
        bareDrain(s);
        s.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s);
        bareDrain(s);

        assertEquals("ни одной записи реквизитов после завершения",
                0, puts.get());
        assertEquals(888, CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("888|"));
        s.onDestroy();
    }

    // ==================== 2Г.4Б.1.1: явный выбор того же адресата
    // и подтверждение маркера перехода ====================

    private static final String OWNER_TRANSITION = "owner_transition";

    /** Подсчитывающий сквозной backend: put делегируется, вызовы логируются. */
    private CredHelper.HookedBackend installCountingBackend(
            java.util.List<String> opLog) {
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new AndroidCredentialBackend(context,
                        c -> CredHelper.enc(context)));
        hb.putOverride = (t, c, a, st) -> {
            opLog.add("cred");
            return hb.d.putCredentials(t, c, a, st);
        };
        CredentialStore.resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;
        return hb;
    }

    /**
     * Дефект 1 точное воспроизведение: pending-сброс завершён (диск=0),
     * пользователь явно выбрал ТОТ ЖЕ чат 777 до следующего обхода —
     * выбор подтверждён собственной записью с флагом явного выбора и
     * не стирается; показание доставляется ровно один раз.
     */
    @Test
    public void sameChatReselectionAfterConfirmedResetPreserved() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));

        // Pending-сброс завершается: чат 0 на диске.
        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(0, CredHelper.getChatId(context));

        // Явный выбор ТОГО ЖЕ значения 777 без промежуточного обхода.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(777,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // Следующий обход: повторный выбор 777 — не наследование.
        s2.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("явный повторный выбор 777 сохранён", 777,
                CredHelper.getChatId(context));
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("777|"));
        s2.onDestroy();
    }

    /**
     * То же для тревожного адресата с тем же значением -555: поле
     * явно переписано — сохранено; обычный чат 777 не выбирался —
     * очищен (независимые решения по двум полям).
     */
    @Test
    public void sameAlertReselectionAfterConfirmedResetPreserved() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        CredHelper.setAlertChatId(context, -555);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));

        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(0, CredHelper.getAlertChatId(context));

        // Явный выбор того же -555 без обхода сервиса.
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setAlertChatIdAsync(-555,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("явный повторный выбор -555 сохранён", -555,
                CredHelper.getAlertChatId(context));
        assertEquals("непереписанный чат очищен как унаследованный", 0,
                CredHelper.getChatId(context));
        s2.onDestroy();
    }

    /**
     * Пересоздание сервиса после подтверждённого сброса и повторного
     * выбора того же чата: мета явного выбора на диске, выбор сохранён,
     * показание доставлено один раз.
     */
    @Test
    public void recreatedServiceKeepsSameValueSelection() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean failing = new AtomicBoolean(true);
        installFailingPutBackend(failing);

        sends.clear();
        VkSenderService s2 = startService();
        s2.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s2);
        assertTrue(sends.isEmpty());
        s2.onDestroy();

        failing.set(false);
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .resolveAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        assertEquals(CredentialStore.Status.READY, Credentials.get(context)
                .setChatIdAsync(777,
                        com.example.xdrip2vk.core.Fingerprint.of("SYNTH_TOKEN_B"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        // «Новый процесс» между выбором и завершением перехода.
        CredHelper.recreate(context);
        VkSenderService s3 = startService();
        bareDrain(s3);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s3);

        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        assertEquals("выбор 777 пережил пересоздание сервиса", 777,
                CredHelper.getChatId(context));
        s3.onStartCommand(autoUpdate(122, System.currentTimeMillis()), 0, 4);
        idle(s3);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0).startsWith("777|"));
        s3.onDestroy();
    }

    /**
     * Дефект 2: commit маркера отклонён с грязным кешем — сброс не
     * начинается, пока маркер не подтверждён на диске успешным commit.
     */
    @Test
    public void markerCommitRequiredBeforeReset() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean markerFailing = new AtomicBoolean(true);
        CredHelper.DirtySharedPreferences dirty =
                new CredHelper.DirtySharedPreferences(prefs,
                        OWNER_TRANSITION, markerFailing);
        VkSenderService.prefsOverride = dirty;
        java.util.List<String> opLog = dirty.opLog;
        installCountingBackend(opLog);

        sends.clear();
        VkSenderService s2 = startService();
        bareDrain(s2);

        // Маркер читается из грязного кеша, на диске его нет: ни одной
        // записи реквизитов и ни одной отправки — сброс не начат.
        assertTrue("commit маркера отклонён", dirty.keyCommits.get() > 0);
        assertFalse("маркер не подтверждён на диске", dirty.keyOnDisk);
        assertTrue("сброс не начат без подтверждённого маркера",
                opLog.indexOf("cred") < 0);
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));

        // Восстановление: успешный commit маркера строго до первой
        // записи сброса.
        markerFailing.set(false);
        bareDrain(s2);
        Credentials.get(context).awaitPublishIdleForTest();
        bareDrain(s2);

        assertTrue("маркер подтверждён на диске", dirty.keyOnDisk);
        int firstMarker = opLog.indexOf("key:true");
        int firstCred = opLog.indexOf("cred");
        assertTrue("успешный commit маркера зафиксирован",
                firstMarker >= 0);
        assertTrue("запись сброса только после подтверждения маркера",
                firstCred < 0 || firstMarker < firstCred);
        assertEquals("user:2", prefs.getString(LAST_SENDER, ""));
        s2.onDestroy();
    }

    /**
     * Маркер продолжает не записываться: ни сбросов, ни отправок в
     * унаследованные чаты, частого цикла нет — одна попытка commit на
     * обход.
     */
    @Test
    public void markerKeepsFailingNoResetNoLoop() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean markerFailing = new AtomicBoolean(true);
        CredHelper.DirtySharedPreferences dirty =
                new CredHelper.DirtySharedPreferences(prefs,
                        OWNER_TRANSITION, markerFailing);
        VkSenderService.prefsOverride = dirty;
        installCountingBackend(dirty.opLog);

        sends.clear();
        VkSenderService s2 = startService();
        bareDrain(s2);
        bareDrain(s2);
        bareDrain(s2);

        assertEquals("ни одной записи реквизитов", -1,
                dirty.opLog.indexOf("cred"));
        assertTrue("отправок в унаследованные чаты нет", sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        assertFalse(dirty.keyOnDisk);
        // Одна попытка commit маркера на ensureOwnerTransition внутри
        // обхода — без внутреннего цикла.
        assertTrue("bounded: попыток " + dirty.keyCommits.get(),
                dirty.keyCommits.get() <= 9);
        s2.onDestroy();
    }

    /**
     * Новый экземпляр сервиса в том же процессе читает маркер из того
     * же грязного кеша — обхода неподтверждённого маркера нет: commit
     * повторяется, сброс не начинается.
     */
    @Test
    public void newInstanceRespectsUnconfirmedMarker() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();

        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        VkSenderService.identityVerifierOverride =
                token -> token.equals("SYNTH_TOKEN_B") ? "user:2" : "user:1";
        AtomicBoolean markerFailing = new AtomicBoolean(true);
        CredHelper.DirtySharedPreferences dirty =
                new CredHelper.DirtySharedPreferences(prefs,
                        OWNER_TRANSITION, markerFailing);
        VkSenderService.prefsOverride = dirty;
        installCountingBackend(dirty.opLog);

        sends.clear();
        VkSenderService s2 = startService();
        bareDrain(s2);
        assertFalse(dirty.keyOnDisk);
        assertEquals(-1, dirty.opLog.indexOf("cred"));
        s2.onDestroy();

        // Новый экземпляр того же процесса: кеш тот же, маркер на
        // диске отсутствует — сброс по-прежнему не начинается.
        VkSenderService s3 = startService();
        bareDrain(s3);
        assertFalse(dirty.keyOnDisk);
        assertEquals(-1, dirty.opLog.indexOf("cred"));
        assertTrue(sends.isEmpty());
        assertEquals("user:1", prefs.getString(LAST_SENDER, ""));
        s3.onDestroy();
    }
}
