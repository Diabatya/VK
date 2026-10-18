package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.store.AndroidCredentialBackend;
import com.example.xdrip2vk.store.CredentialBackend;

import com.example.xdrip2vk.store.CredentialStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Фасад Credentials поверх настоящего AndroidCredentialBackend (граница
 * открытия encrypted подменена SharedPreferences/исключением — Keystore
 * как таковой этими тестами не проверяется). Заменяет
 * TokenStorageDefectTest: вместо документирования дефектов fallback
 * проверяется его отсутствие в действующем пути приложения.
 * Все значения синтетические.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CredentialsFacadeTest {

    /** Обёртка над настоящим backend с хуками записи/вызова. */
    private static class WrappingBackend implements CredentialBackend {
        final CredentialBackend d;
        java.util.function.Supplier<Boolean> putOverride;
        Runnable onAnyCall;
        WrappingBackend(CredentialBackend d) { this.d = d; }
        @Override public Object storageId() { return d.storageId(); }
        @Override public Source snapshot(Role role) {
            if (onAnyCall != null) onAnyCall.run();
            return d.snapshot(role);
        }
        @Override public ServiceState serviceState(Role role) {
            return d.serviceState(role);
        }
        @Override public boolean putCredentials(String t, long c, long a, String st) {
            if (onAnyCall != null) onAnyCall.run();
            return putOverride != null ? putOverride.get()
                    : d.putCredentials(t, c, a, st);
        }
        @Override public boolean deleteCredentials() { return d.deleteCredentials(); }
        @Override public boolean removeCredentials(Role role) {
            return d.removeCredentials(role);
        }
    }

    private Context context;
    private SharedPreferences defaultPrefs;
    private SharedPreferences plainPrefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        defaultPrefs.edit().clear().commit();
        plainPrefs = context.getSharedPreferences("secure_tokens_plain",
                Context.MODE_PRIVATE);
        plainPrefs.edit().clear().commit();
    }

    private void installBackend(CredentialBackend b) {
        CredentialStore.resetCoordinatorForTest(b);
        Credentials.resetForTest();
        Credentials.backendForTest = b;
        Credentials.permitMainBlockingForTest = true;
    }

    /** Backend, у которого encrypted всегда недоступен (Keystore-бросок). */
    private void installEncryptedUnavailable() {
        installBackend(new AndroidCredentialBackend(context,
                ctx -> { throw new SecurityException("synthetic keystore down"); }));
    }

    /** Backend, чей commit записи всегда возвращает false. */
    private void installCommitFalse() {
        SharedPreferences enc = CredHelper.enc(context);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = () -> false; // commit не подтверждён
        installBackend(b);
    }

    private CredentialStore.Resolution await(Future<CredentialStore.Resolution> f)
            throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    /**
     * Недоступный encrypted: приложение НЕ пишет токен в plaintext и
     * не разрешает отправку — в отличие от старого молчаливого fallback.
     */
    @Test
    public void encryptedUnavailableWritesNothingToPlaintext() throws Exception {
        installEncryptedUnavailable();

        CredentialStore.Resolution r =
                await(Credentials.get(context).setTokenAsync("SYNTH_TOKEN_AAA"));

        assertNotEquals(CredentialStore.Status.READY, r.status);
        assertFalse("токен не попал в plaintext-файл",
                plainPrefs.contains("vk_token"));
        assertFalse(defaultPrefs.contains("vk_token"));
        assertFalse("снимок не выдаёт разрешение отправки",
                Credentials.get(context).snapshot().sendPermitted());
    }

    /**
     * Недоступный encrypted + legacy-реквизиты в default: legacy НЕ
     * используется как fallback для отправки и не очищается автоматически.
     */
    @Test
    public void encryptedUnavailableKeepsLegacyUntouchedAndBlocks() throws Exception {
        installEncryptedUnavailable();
        defaultPrefs.edit()
                .putString("vk_token", "SYNTH_TOKEN_LEGACY")
                .putLong("vk_chat_id", 123L)
                .commit();

        Credentials.Snapshot s = Credentials.get(context).resolveBlocking();

        assertEquals(Credentials.State.BLOCKED, s.state);
        assertFalse(s.sendPermitted());
        // Legacy не стёрт и не скопирован в plaintext-файл.
        assertEquals("SYNTH_TOKEN_LEGACY", defaultPrefs.getString("vk_token", ""));
        assertFalse(plainPrefs.contains("vk_token"));
    }

    /**
     * Конфликт: разные токены в enc и default — ничего не перезаписано,
     * разрешения нет, оба источника сохранены.
     */
    @Test
    public void conflictNotResolvedSilently() throws Exception {
        CredHelper.enc(context).edit()
                .putString("vk_token", "SYNTH_TOKEN_BBB").commit();
        defaultPrefs.edit().putString("vk_token", "SYNTH_TOKEN_CCC").commit();

        Credentials.Snapshot s = Credentials.get(context).resolveBlocking();

        assertEquals(Credentials.State.BLOCKED, s.state);
        assertFalse(s.sendPermitted());
        assertEquals("SYNTH_TOKEN_BBB",
                CredHelper.enc(context).getString("vk_token", ""));
        assertEquals("SYNTH_TOKEN_CCC",
                defaultPrefs.getString("vk_token", ""));
    }

    /**
     * Legacy-данные только в default: подтверждённая миграция в encrypted,
     * источник очищен, plaintext-файл не создан.
     */
    @Test
    public void legacyDefaultOnlyMigratesConfirmed() throws Exception {
        defaultPrefs.edit()
                .putString("vk_token", "SYNTH_TOKEN_LEGACY")
                .putLong("vk_chat_id", 456L)
                .putLong("alert_chat_id", -789L)
                .commit();

        Credentials.Snapshot s = Credentials.get(context).resolveBlocking();

        assertEquals(Credentials.State.READY, s.state);
        assertEquals("SYNTH_TOKEN_LEGACY", s.token);
        assertEquals(456L, s.chatId);
        assertEquals(-789L, s.alertChatId);
        assertEquals("SYNTH_TOKEN_LEGACY",
                CredHelper.enc(context).getString("vk_token", ""));
        assertFalse(defaultPrefs.contains("vk_token"));
        assertFalse(defaultPrefs.contains("vk_chat_id"));
        assertFalse(plainPrefs.contains("vk_token"));
        // Служебное состояние помечено как активное.
        assertEquals(CredentialBackend.STATE_ACTIVE,
                CredHelper.enc(context).getString("cred_service_state", ""));
    }

    /**
     * Ручной ввод через фасад: постоянная копия токена — только в
     * encrypted-файле, ни в plaintext, ни в default prefs.
     */
    @Test
    public void manualEntryLeavesNoPlaintextCopy() throws Exception {
        CredentialStore.Resolution r =
                await(Credentials.get(context).setTokenAsync("SYNTH_TOKEN_MANUAL"));

        assertEquals(CredentialStore.Status.READY, r.status);
        assertEquals("SYNTH_TOKEN_MANUAL",
                CredHelper.enc(context).getString("vk_token", ""));
        assertFalse("нет plaintext-копии", plainPrefs.contains("vk_token"));
        assertFalse("токен не попал в default prefs",
                defaultPrefs.contains("vk_token"));
    }

    /**
     * commit=false: нет ложного успеха — статус не READY, отправка не
     * разрешена, реквизиты не опубликованы.
     */
    @Test
    public void commitFalseGivesNoFalseSuccess() throws Exception {
        installCommitFalse();

        CredentialStore.Resolution r =
                await(Credentials.get(context).setTokenAsync("SYNTH_TOKEN_X"));

        assertEquals(CredentialStore.Status.INCOMPLETE, r.status);
        Credentials.Snapshot s = Credentials.get(context).snapshot();
        assertFalse("неподтверждённый кеш не публикуется как READY",
                s.sendPermitted());
        assertEquals(Credentials.State.INCOMPLETE, s.state);
    }

    /**
     * Пока мутация в полёте, опубликованный снимок не выдаёт старый READY:
     * блокировка новой отправки действует с момента запроса.
     */
    @Test
    public void mutationInFlightBlocksOldSnapshot() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 5);
        CredentialStore.Resolution ready =
                await(Credentials.get(context).resolveAsync());
        assertEquals(CredentialStore.Status.READY, ready.status);
        // Опубликованный снимок — READY.
        assertTrue(Credentials.get(context).snapshot().sendPermitted());

        // Блокирующий backend: putCredentials ждёт разрешения теста.
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        SharedPreferences enc = CredHelper.enc(context);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return b.d.putCredentials("SYNTH_TOKEN_B", 5, 0,
                    CredentialBackend.STATE_ACTIVE);
        };
        installBackend(b);

        Credentials.get(context).resolveAsync().get(10, TimeUnit.SECONDS);
        assertTrue(Credentials.get(context).snapshot().sendPermitted());

        Future<CredentialStore.Resolution> pending =
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_B");
        assertTrue("запись не началась", entered.await(10, TimeUnit.SECONDS));
        // Во время неподтверждённой записи снимок — не старый READY.
        assertFalse("старый READY не виден во время записи",
                Credentials.get(context).snapshot().sendPermitted());
        assertEquals(Credentials.State.INCOMPLETE,
                Credentials.get(context).snapshot().state);

        release.countDown();
        CredentialStore.Resolution r = pending.get(10, TimeUnit.SECONDS);
        assertEquals(CredentialStore.Status.READY, r.status);
        // Нужен ещё один цикл публикации на потоке фасада.
        Thread.sleep(50);
        assertEquals("SYNTH_TOKEN_B",
                Credentials.get(context).resolveBlocking().token);
    }

    /**
     * Подтверждённое удаление переживает пересоздание фасада (новый
     * «процесс» того же устройства): токен не воскресает.
     */
    @Test
    public void confirmedDeleteSurvivesFacadeRecreation() throws Exception {
        CredentialStore.Resolution saved =
                await(Credentials.get(context).setTokenAsync("SYNTH_TOKEN_DEL"));
        assertEquals(CredentialStore.Status.READY, saved.status);

        CredentialStore.Resolution del =
                await(Credentials.get(context).deleteAsync());
        assertEquals(CredentialStore.Status.DELETED, del.status);

        // «Пересоздание» приложения — новый фасад над теми же файлами
        // (файлы не очищаются — моделируем рестарт процесса).
        CredHelper.recreate(context);
        Credentials.Snapshot s = Credentials.get(context).resolveBlocking();
        assertEquals(Credentials.State.DELETED, s.state);
        assertFalse(s.sendPermitted());
        assertFalse(CredHelper.enc(context).contains("vk_token"));
    }

    /**
     * resolveBlocking с главного потока запрещён — Keystore/диск не
     * выполняются на UI-потоке (защита активна без тестовой подмены).
     */
    @Test
    public void resolveBlockingForbiddenOnMainThread() {
        Credentials.permitMainBlockingForTest = false;
        try {
            Credentials.get(context).resolveBlocking();
            fail("resolveBlocking на главном потоке должен быть запрещён");
        } catch (IllegalStateException expected) {
            // ожидание
        } finally {
            Credentials.permitMainBlockingForTest = true;
        }
    }

    /**
     * Дисковые операции backend выполняются на worker координатора,
     * а не на главном потоке — проверяется по имени потока вызовов.
     */
    @Test
    public void storageOpsRunOffMainThread() throws Exception {
        SharedPreferences enc = CredHelper.enc(context);
        final AtomicReference<String> threadName = new AtomicReference<>("");
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.onAnyCall = () -> threadName.set(Thread.currentThread().getName());
        installBackend(b);

        CredentialStore.Resolution r =
                await(Credentials.get(context).setTokenAsync("SYNTH_TOKEN_T"));
        assertEquals(CredentialStore.Status.READY, r.status);
        assertTrue("операции хранилища на worker координатора: " + threadName.get(),
                threadName.get().startsWith("credential-store"));
    }
}
