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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
}
