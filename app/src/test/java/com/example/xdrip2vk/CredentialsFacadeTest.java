package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.Fingerprint;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Обёртка над настоящим backend с хуками записи/чтения/вызова. */
    private static class WrappingBackend implements CredentialBackend {
        /** Подмена записи: получает аргументы, возвращает результат commit. */
        interface PutCall {
            boolean call(String token, long chatId, long alertChatId, String state);
        }
        final CredentialBackend d;
        PutCall putOverride;
        java.util.function.Function<Role, Source> snapshotOverride;
        Runnable onAnyCall;
        WrappingBackend(CredentialBackend d) { this.d = d; }
        @Override public Object storageId() { return d.storageId(); }
        @Override public Source snapshot(Role role) {
            if (onAnyCall != null) onAnyCall.run();
            return snapshotOverride != null
                    ? snapshotOverride.apply(role) : d.snapshot(role);
        }
        @Override public ServiceState serviceState(Role role) {
            return d.serviceState(role);
        }
        @Override public boolean putCredentials(String t, long c, long a, String st) {
            if (onAnyCall != null) onAnyCall.run();
            return putOverride != null ? putOverride.call(t, c, a, st)
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
        b.putOverride = (t, c, a, st) -> false; // commit не подтверждён
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
        Credentials.get(context).awaitPublishIdleForTest();
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
        // Опубликованный снимок — READY (публикация идёт после future).
        Credentials.get(context).awaitPublishIdleForTest();
        assertTrue(Credentials.get(context).snapshot().sendPermitted());

        // Блокирующий backend: putCredentials ждёт разрешения теста.
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        SharedPreferences enc = CredHelper.enc(context);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);

        Credentials.get(context).resolveAsync().get(10, TimeUnit.SECONDS);
        Credentials.get(context).awaitPublishIdleForTest();
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
        Credentials.get(context).awaitPublishIdleForTest();
        Credentials.Snapshot after = Credentials.get(context).snapshot();
        assertTrue(after.sendPermitted());
        assertEquals("SYNTH_TOKEN_B", after.token);
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

    // ==================== 2Г.4А: протокол поколений ====================

    /**
     * Точное воспроизведение №1: setTokenAsync(B) удержан → resolveAsync
     * между запросом и завершением записи → после завершения записи и
     * чтения запрет снят, снимок — READY(B). Раньше чтение инвалидировало
     * учёт завершения записи и mutationPending оставался навсегда.
     */
    @Test
    public void readDuringMutationDoesNotLeaveStuckBlock() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        enc.edit().putString("vk_token", "SYNTH_TOKEN_A")
                .putLong("vk_chat_id", 5).commit();
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        assertEquals(CredentialStore.Status.READY,
                await(creds.resolveAsync()).status);
        creds.awaitPublishIdleForTest();
        assertTrue(creds.snapshot().sendPermitted());

        Future<CredentialStore.Resolution> write =
                creds.setTokenAsync("SYNTH_TOKEN_B"); // gen N
        assertTrue("запись вошла и удержана", entered.await(10, TimeUnit.SECONDS));
        Future<CredentialStore.Resolution> read = creds.resolveAsync(); // gen N+1
        assertEquals(Credentials.State.INCOMPLETE, creds.snapshot().state);

        release.countDown();
        assertEquals(CredentialStore.Status.READY,
                write.get(10, TimeUnit.SECONDS).status);
        assertEquals(CredentialStore.Status.READY,
                read.get(10, TimeUnit.SECONDS).status);
        creds.awaitPublishIdleForTest();
        Credentials.Snapshot s = creds.snapshot();
        assertEquals("запрет не завис: чтение не отменило учёт записи",
                Credentials.State.READY, s.state);
        assertEquals("SYNTH_TOKEN_B", s.token);
    }

    /**
     * Две записи и чтение между ними: завершение первой НЕ снимает запрет
     * второй — между ними снимок не возвращается к READY.
     */
    @Test
    public void firstMutationDoesNotLiftSecondBlock() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        enc.edit().putString("vk_token", "SYNTH_TOKEN_A")
                .putLong("vk_chat_id", 5).commit();
        final java.util.concurrent.CountDownLatch entB =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch relB =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch entC =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch relC =
                new java.util.concurrent.CountDownLatch(1);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> {
            if ("SYNTH_TOKEN_B".equals(t)) {
                entB.countDown();
                try { relB.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else if ("SYNTH_TOKEN_C".equals(t)) {
                entC.countDown();
                try { relC.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        Future<CredentialStore.Resolution> m1 = creds.setTokenAsync("SYNTH_TOKEN_B");
        assertTrue(entB.await(10, TimeUnit.SECONDS));       // put(B) удержан
        Future<CredentialStore.Resolution> r = creds.resolveAsync();
        Future<CredentialStore.Resolution> m2 = creds.setTokenAsync("SYNTH_TOKEN_C");

        relB.countDown();
        assertEquals(CredentialStore.Status.READY,
                m1.get(10, TimeUnit.SECONDS).status);       // первая запись завершена
        assertFalse("первая запись не сняла запрет второй",
                creds.snapshot().sendPermitted());

        relC.countDown();
        CredentialStore.Resolution readRes = r.get(10, TimeUnit.SECONDS);
        assertEquals(CredentialStore.Status.READY, readRes.status);
        assertEquals("чтение исполнилось после commit B", "SYNTH_TOKEN_B",
                readRes.token);
        assertEquals(CredentialStore.Status.READY,
                m2.get(10, TimeUnit.SECONDS).status);
        creds.awaitPublishIdleForTest();
        Credentials.Snapshot s = creds.snapshot();
        assertEquals(Credentials.State.READY, s.state);
        assertEquals("SYNTH_TOKEN_C", s.token);
        assertEquals(5, s.chatId); // адресаты сохранены через обе записи
    }

    /**
     * Точное воспроизведение №2: удержанное чтение A + запрошенная запись
     * B → resolveBlocking возвращает блокирующее состояние, а не старый
     * READY с A. Запись удерживается до завершения resolveBlocking.
     */
    @Test
    public void resolveBlockingNeverReturnsStaleReady() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        enc.edit().putString("vk_token", "SYNTH_TOKEN_A")
                .putLong("vk_chat_id", 5).commit();
        final java.util.concurrent.CountDownLatch readEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch readRelease =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch putEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch putRelease =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean holdRead = new AtomicBoolean(false);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.snapshotOverride = role -> {
            if (role == Role.ENCRYPTED && holdRead.get()) {
                readEntered.countDown();
                try { readRelease.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return b.d.snapshot(role);
        };
        b.putOverride = (t, c, a, st) -> {
            putEntered.countDown();
            try { putRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();
        assertTrue(creds.snapshot().sendPermitted());

        // Фоновый resolveBlocking начинает чтение A — удерживаем его.
        holdRead.set(true);
        final AtomicReference<Credentials.Snapshot> blocked =
                new AtomicReference<>();
        Thread caller = new Thread(() -> blocked.set(creds.resolveBlocking()));
        caller.start();
        assertTrue("чтение A началось и удержано",
                readEntered.await(10, TimeUnit.SECONDS));

        // Запрос записи B: немедленный запрет уже установлен.
        Future<CredentialStore.Resolution> mut =
                creds.setTokenAsync("SYNTH_TOKEN_B");
        assertFalse(creds.snapshot().sendPermitted());

        // Чтение завершается со старым A; запись B удержана.
        readRelease.countDown();
        assertTrue("запись B удержана", putEntered.await(10, TimeUnit.SECONDS));
        caller.join(10_000);

        Credentials.Snapshot s = blocked.get();
        assertNotNull("resolveBlocking завершился", s);
        assertFalse("устаревшее чтение не разрешило отправку",
                s.sendPermitted());
        assertEquals(Credentials.State.INCOMPLETE, s.state);

        putRelease.countDown();
        assertEquals(CredentialStore.Status.READY,
                mut.get(10, TimeUnit.SECONDS).status);
        creds.awaitPublishIdleForTest();
        assertEquals("SYNTH_TOKEN_B", creds.snapshot().token);
    }

    /**
     * Поздний main-callback старого чтения не обновляет экран устаревшим
     * READY: в момент доставки действует текущий запрет/состояние.
     */
    @Test
    public void staleMainCallbackDeliversNoOldReady() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        enc.edit().putString("vk_token", "SYNTH_TOKEN_A")
                .putLong("vk_chat_id", 5).commit();
        final java.util.concurrent.CountDownLatch readEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch readRelease =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch putRelease =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean holdRead = new AtomicBoolean(false);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.snapshotOverride = role -> {
            if (role == Role.ENCRYPTED && holdRead.get()) {
                readEntered.countDown();
                try { readRelease.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return b.d.snapshot(role);
        };
        b.putOverride = (t, c, a, st) -> {
            try { putRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        holdRead.set(true);
        final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Credentials.Snapshot> got =
                new AtomicReference<>();
        creds.resolveOnMain(s -> {
            got.set(s);
            delivered.countDown();
        });
        assertTrue(readEntered.await(10, TimeUnit.SECONDS));
        readRelease.countDown();
        // Маркер за задачей доставки гарантирует: callback уже в очереди
        // main — модель «callback ждал в очереди после завершения чтения».
        creds.awaitPublishIdleForTest();
        // Более новый запрос — запись B (удержана) — пришёл ПОСЛЕ того,
        // как callback старого чтения ждал доставки.
        Future<CredentialStore.Resolution> mut =
                creds.setTokenAsync("SYNTH_TOKEN_B");
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idle();
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        Credentials.Snapshot s = got.get();
        // Доставленный снимок — актуальное состояние с учётом запрета;
        // устаревший READY(A) недопустим.
        assertFalse("SYNTH_TOKEN_A".equals(s.token) && s.sendPermitted());
        assertEquals(Credentials.State.INCOMPLETE, s.state);

        putRelease.countDown();
        assertEquals(CredentialStore.Status.READY,
                mut.get(10, TimeUnit.SECONDS).status);
    }

    /**
     * Аналог для background-callback: поздний результат старого чтения
     * не приходит как действующее разрешение.
     */
    @Test
    public void staleBackgroundCallbackDeliversNoOldReady() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        enc.edit().putString("vk_token", "SYNTH_TOKEN_A")
                .putLong("vk_chat_id", 5).commit();
        final java.util.concurrent.CountDownLatch readEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch readRelease =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch putRelease =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean holdRead = new AtomicBoolean(false);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.snapshotOverride = role -> {
            if (role == Role.ENCRYPTED && holdRead.get()) {
                readEntered.countDown();
                try { readRelease.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return b.d.snapshot(role);
        };
        b.putOverride = (t, c, a, st) -> {
            try { putRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        holdRead.set(true);
        final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Credentials.Snapshot> got =
                new AtomicReference<>();
        creds.resolveOnBackground(s -> {
            got.set(s);
            delivered.countDown();
        });
        assertTrue(readEntered.await(10, TimeUnit.SECONDS));
        Future<CredentialStore.Resolution> mut =
                creds.setTokenAsync("SYNTH_TOKEN_B");
        readRelease.countDown();

        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        Credentials.Snapshot s = got.get();
        assertFalse("фоновый callback не получил старый READY(A)",
                "SYNTH_TOKEN_A".equals(s.token) && s.sendPermitted());

        putRelease.countDown();
        assertEquals(CredentialStore.Status.READY,
                mut.get(10, TimeUnit.SECONDS).status);
    }

    /**
     * Два вызывающих потока: порядок исполнения операций соответствует
     * порядку выдачи поколений — последняя принятая запись побеждает
     * и на диске, и в опубликованном снимке.
     */
    @Test
    public void callerOrderDefinesOperationOrder() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        final java.util.List<String> puts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.concurrent.CountDownLatch firstPut =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch relFirst =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean firstSeen = new AtomicBoolean(true);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> {
            puts.add(t);
            if (firstSeen.compareAndSet(true, false)) {
                firstPut.countDown();
                try { relFirst.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        // T1 ставит X, полностью выдаёт свой запрос до старта T2.
        final java.util.concurrent.CountDownLatch t1Done =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Future<CredentialStore.Resolution>> f1 =
                new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            f1.set(creds.setTokenAsync("SYNTH_TOKEN_X"));
            t1Done.countDown();
        });
        t1.start();
        assertTrue(t1Done.await(10, TimeUnit.SECONDS));
        assertTrue("первая запись исполняется", firstPut.await(10, TimeUnit.SECONDS));

        final AtomicReference<Future<CredentialStore.Resolution>> f2 =
                new AtomicReference<>();
        Thread t2 = new Thread(() -> f2.set(creds.setTokenAsync("SYNTH_TOKEN_Y")));
        t2.start();
        t2.join(10_000);

        relFirst.countDown();
        assertEquals(CredentialStore.Status.READY,
                f1.get().get(10, TimeUnit.SECONDS).status);
        assertEquals(CredentialStore.Status.READY,
                f2.get().get(10, TimeUnit.SECONDS).status);
        creds.awaitPublishIdleForTest();

        assertEquals(java.util.Arrays.asList("SYNTH_TOKEN_X", "SYNTH_TOKEN_Y"),
                puts);
        assertEquals("SYNTH_TOKEN_Y", CredHelper.getToken(context));
        assertEquals("SYNTH_TOKEN_Y", creds.snapshot().token);
    }

    /**
     * Одновременные записи двух потоков: итог диска и опубликованного
     * снимка равен последней ИСПОЛНЕННОЙ записи — порядок операций и
     * порядок публикаций не расходятся.
     */
    @Test
    public void concurrentMutationsStayConsistent() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        final java.util.List<String> puts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> {
            puts.add(t);
            return b.d.putCredentials(t, c, a, st);
        };
        installBackend(b);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        final java.util.concurrent.CyclicBarrier start =
                new java.util.concurrent.CyclicBarrier(3);
        final AtomicReference<Future<CredentialStore.Resolution>> f1 =
                new AtomicReference<>();
        final AtomicReference<Future<CredentialStore.Resolution>> f2 =
                new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                f1.set(creds.setTokenAsync("SYNTH_TOKEN_X"));
            } catch (Exception ignored) { }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                f2.set(creds.setTokenAsync("SYNTH_TOKEN_Y"));
            } catch (Exception ignored) { }
        });
        t1.start();
        t2.start();
        start.await(10, TimeUnit.SECONDS);
        t1.join(10_000);
        t2.join(10_000);
        assertNotNull(f1.get());
        assertNotNull(f2.get());
        f1.get().get(10, TimeUnit.SECONDS);
        f2.get().get(10, TimeUnit.SECONDS);
        creds.awaitPublishIdleForTest();

        assertEquals("порядок записей = порядок поколений, 2 записи", 2, puts.size());
        String last = puts.get(puts.size() - 1);
        assertEquals(last, CredHelper.getToken(context));
        Credentials.Snapshot s = creds.snapshot();
        assertEquals(Credentials.State.READY, s.state);
        assertEquals("опубликованный снимок = последняя исполненная запись",
                last, s.token);
    }

    /**
     * commit=false сохраняет блокировку; следующий resolve повторяет
     * реальный commit и публикует актуальный READY — запрет не вечный.
     */
    @Test
    public void commitFalseKeepsBlockThenRecoveryPublishesReady() throws Exception {
        final SharedPreferences enc = CredHelper.enc(context);
        final AtomicBoolean failNext = new AtomicBoolean(true);
        WrappingBackend b = new WrappingBackend(
                new AndroidCredentialBackend(context, ctx -> enc));
        b.putOverride = (t, c, a, st) -> !failNext.getAndSet(false)
                && b.d.putCredentials(t, c, a, st);
        installBackend(b);
        Credentials creds = Credentials.get(context);

        CredentialStore.Resolution r1 =
                await(creds.setTokenAsync("SYNTH_TOKEN_B"));
        assertEquals(CredentialStore.Status.INCOMPLETE, r1.status);
        creds.awaitPublishIdleForTest();
        assertFalse("commit=false сохраняет блокировку",
                creds.snapshot().sendPermitted());

        // Следующее чтение повторяет commit на диске — запрет снимается
        // подтверждённым результатом, а не устаревшим состоянием.
        CredentialStore.Resolution r2 = await(creds.resolveAsync());
        assertEquals(CredentialStore.Status.READY, r2.status);
        creds.awaitPublishIdleForTest();
        Credentials.Snapshot s = creds.snapshot();
        assertTrue(s.sendPermitted());
        assertEquals("SYNTH_TOKEN_B", s.token);
        assertEquals("SYNTH_TOKEN_B", CredHelper.getToken(context));
    }

    // ==================== 2Г.4А.1: исход команды vs состояние ====================

    /**
     * Точное воспроизведение №2Г.4А.1: отклонённая команда выбора чата
     * (STALE_TOKEN) + последующий resolve READY(B,777) → задержанный
     * callback получает ИСХОД СВОЕЙ команды, а не чужой READY.
     */
    @Test
    public void rejectedCommandKeepsOwnOutcome() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_B");
        CredHelper.setChatId(context, 777);
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Credentials.CommandResult> got =
                new AtomicReference<>();
        // Список был загружен с токеном A — fingerprint устарел.
        creds.setChatIdOnMain(999, Fingerprint.of("SYNTH_TOKEN_A"), r -> {
            got.set(r);
            delivered.countDown();
        });
        // Команда исполнена и её callback поставлен в main-очередь,
        // но ещё не исполнен — моделируем отложенную доставку.
        creds.awaitPublishIdleForTest();
        // Более новое чтение публикует READY(B, 777).
        CredentialStore.Resolution r2 = await(creds.resolveAsync());
        assertEquals(CredentialStore.Status.READY, r2.status);
        assertEquals("SYNTH_TOKEN_B", r2.token);
        creds.awaitPublishIdleForTest();

        // Исполняем задержанный callback.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idle();
        assertTrue(delivered.await(10, TimeUnit.SECONDS));

        Credentials.CommandResult cr = got.get();
        assertNotNull(cr);
        assertEquals("отказ команды сохранён, чужой READY его не закрыл",
                Credentials.State.BLOCKED, cr.outcome.state);
        assertEquals(Reason.STALE_TOKEN, cr.outcome.reason);
        assertFalse("чтение не замещает команду", cr.superseded);
        assertFalse(cr.outcome.sendPermitted());
        assertEquals("адресат 777 не изменён", 777,
                CredHelper.getChatId(context));
    }

    /**
     * Подтверждённая старая запись, замещённая более новым изменением до
     * доставки callback: outcome — её собственный READY, но superseded
     * запрещает показывать старое значение как текущее.
     */
    @Test
    public void supersededSuccessFlaggedNotCurrent() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        Credentials creds = Credentials.get(context);
        await(creds.resolveAsync());
        creds.awaitPublishIdleForTest();

        final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Credentials.CommandResult> got =
                new AtomicReference<>();
        creds.setTokenOnMain("SYNTH_TOKEN_B", r -> {
            got.set(r);
            delivered.countDown();
        });
        // Команда подтверждена, callback ждёт в main-очереди.
        creds.awaitPublishIdleForTest();
        // Более новое изменение принято до исполнения callback.
        assertEquals(CredentialStore.Status.READY,
                await(creds.setTokenAsync("SYNTH_TOKEN_C")).status);
        creds.awaitPublishIdleForTest();

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idle();
        assertTrue(delivered.await(10, TimeUnit.SECONDS));

        Credentials.CommandResult cr = got.get();
        assertEquals("исход самой команды сохранён",
                Credentials.State.READY, cr.outcome.state);
        assertEquals("SYNTH_TOKEN_B", cr.outcome.token);
        assertTrue("успех замещён более новым изменением", cr.superseded);
        assertEquals("SYNTH_TOKEN_C", creds.snapshot().token);
        assertEquals("SYNTH_TOKEN_C", CredHelper.getToken(context));
    }
}
