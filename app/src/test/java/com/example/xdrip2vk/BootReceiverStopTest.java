package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.Fingerprint;
import com.example.xdrip2vk.store.CredentialStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowApplication;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2Г.4В — запрет отложенного автозапуска после Stop. Настоящий
 * BootReceiver: разрешение проверяется до async resolve, но действующее
 * разрешение перепроверяется непосредственно перед запуском из
 * отложенного callback'а (и в синхронной ветке — одно правило).
 * Чтение backend удерживается на latch, Stop — настоящий
 * requestStopTransmission, запуски проверяются по фактически
 * запрошенным сервисам. Без sleep и без подмены результата
 * isTransmissionAllowed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BootReceiverStopTest {

    private Context context;
    private SharedPreferences prefs;
    private CredHelper.HookedBackend hooked;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        hooked = new CredHelper.HookedBackend(Credentials.backendForTest);
        CredentialStore.resetCoordinatorForTest(hooked);
        Credentials.backendForTest = hooked;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        prefs.edit().putBoolean(
                VkSenderService.PREF_TRANSMISSION_ENABLED, true).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        // SDK 34: receiver проверяет разрешение уведомлений до запуска.
        shadowOf((Application) context)
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void tearDown() {
        VkSenderService.transmissionWriteOverride = null;
        VkSenderService.resetTransmissionFlagsForTest();
    }

    private ShadowApplication shadowApp() {
        return shadowOf((Application) context);
    }

    private Intent nextStarted() {
        return shadowApp().getNextStartedService();
    }

    private void fireBoot() {
        new BootReceiver().onReceive(context,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
    }

    /** Сколько retry-будильников сейчас назначено (extra confirm_retry). */
    private int retryAlarmsScheduled() {
        int n = 0;
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (ShadowAlarmManager.ScheduledAlarm a
                : shadowOf(am).getScheduledAlarms()) {
            Intent saved = shadowOf(a.operation).getSavedIntent();
            if (saved != null && saved.getBooleanExtra("confirm_retry", false)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Барьер чтения ENCRYPTED-снимка: resolve висит на worker
     * координатора, пока тест не освободит чтение.
     */
    private static final class ReadGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);
    }

    private ReadGate holdCredentialRead() {
        ReadGate gate = new ReadGate();
        hooked.snapshotOverride = role -> {
            if (gate.armed.get() && role == Role.ENCRYPTED) {
                gate.entered.countDown();
                try {
                    gate.release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return hooked.d.snapshot(role);
        };
        return gate;
    }

    /** Настоящий Stop; возвращает подтверждение записи запрета. */
    private boolean requestStopAndAwait() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        VkSenderService.requestStopTransmission(context, ok -> {
            saved.set(ok);
            done.countDown();
        });
        assertTrue("requestStopTransmission не завершился",
                done.await(10, TimeUnit.SECONDS));
        return saved.get();
    }

    /**
     * Воспроизведение дефекта: передача разрешена, реквизиты ещё
     * LOADING → async resolve удержан → настоящий Stop подтверждён →
     * чтение возвращает настроенный READY → запоздавший callback не
     * запускает сервис и не назначает новых попыток подтверждения.
     */
    @Test
    public void heldResolveThenConfirmedStopDoesNotStartService()
            throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        ReadGate gate = holdCredentialRead();

        fireBoot();
        assertTrue("resolve не дошёл до чтения backend",
                gate.entered.await(10, TimeUnit.SECONDS));

        assertTrue("Stop не подтверждён записью", requestStopAndAwait());
        assertFalse(VkSenderService.isTransmissionAllowed(context));

        gate.armed.set(false);
        gate.release.countDown();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull("запоздавший READY после Stop запустил сервис",
                nextStarted());
        assertEquals("запоздавший callback назначил повторную проверку",
                0, retryAlarmsScheduled());
    }

    /**
     * Отказ записи Stop: процессный запрет (userStopInFlight) действует
     * без подтверждения диска — отложенный callback сервис не запускает.
     */
    @Test
    public void heldResolveThenFailedStopWriteDoesNotStartService()
            throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        VkSenderService.transmissionWriteOverride =
                (enabled, baseline) -> false;
        ReadGate gate = holdCredentialRead();

        fireBoot();
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));

        assertFalse("отказ записи должен вернуть saved=false",
                requestStopAndAwait());
        assertFalse("процессный запрет при отказе записи Stop",
                VkSenderService.isTransmissionAllowed(context));

        gate.armed.set(false);
        gate.release.countDown();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull("отказ записи Stop не должен разблокировать запуск",
                nextStarted());
        assertEquals(0, retryAlarmsScheduled());
    }

    /** Без Stop: удержанный resolve освобождён READY — сервис запущен. */
    @Test
    public void heldResolveWithoutStopStartsService() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        ReadGate gate = holdCredentialRead();

        fireBoot();
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));

        gate.armed.set(false);
        gate.release.countDown();
        Credentials.get(context).awaitPublishIdleForTest();

        Intent svc = nextStarted();
        assertNotNull("настроенный READY без Stop должен запускать сервис",
                svc);
        assertEquals(VkSenderService.class.getName(),
                svc.getComponent().getClassName());
    }

    /** Синхронная ветка: снимок уже опубликован READY — одно правило. */
    @Test
    public void publishedReadySyncBranchStartsService() {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        Credentials.get(context).resolveBlocking();
        Credentials.get(context).awaitPublishIdleForTest();
        assertTrue(Credentials.get(context).snapshot().configured());

        fireBoot();

        Intent svc = nextStarted();
        assertNotNull(svc);
        assertEquals(VkSenderService.class.getName(),
                svc.getComponent().getClassName());
    }

    /** EMPTY: реквизитов нет — ни синхронная, ни async ветка не запускает. */
    @Test
    public void emptySnapshotDoesNotStartService() {
        fireBoot();
        Credentials.get(context).awaitPublishIdleForTest();
        assertNull(nextStarted());
    }

    /** DELETED: подтверждённое удаление не запускает сервис. */
    @Test
    public void deletedSnapshotDoesNotStartService() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        assertEquals(CredentialStore.Status.DELETED, Credentials.get(context)
                .deleteAsync().get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        fireBoot();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull(nextStarted());
    }

    /** BLOCKED: encrypted-источник недоступен — сервис не запускается. */
    @Test
    public void blockedSnapshotDoesNotStartService() {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        hooked.snapshotOverride = role -> role == Role.ENCRYPTED
                ? Source.unavailable(role) : hooked.d.snapshot(role);

        fireBoot();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull(nextStarted());
    }

    /**
     * INCOMPLETE: незавершённая операция записи (commit=false) — pending
     * повторяется в resolve, снимок не READY, сервис не запускается.
     */
    @Test
    public void incompleteSnapshotDoesNotStartService() throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        hooked.putOverride = (t, c, a, st) -> false;
        assertEquals(CredentialStore.Status.INCOMPLETE, Credentials.get(context)
                .setChatIdAsync(999, Fingerprint.of("SYNTH_TOKEN_A"))
                .get(10, TimeUnit.SECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        fireBoot();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull(nextStarted());
    }

    /** Сохранённый Stop при входе продолжает блокировать запуск. */
    @Test
    public void savedStopBlocksBootStart() {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false)
                .commit();
        VkSenderService.resetTransmissionFlagsForTest();

        fireBoot();
        Credentials.get(context).awaitPublishIdleForTest();

        assertNull(nextStarted());
        assertEquals(0, retryAlarmsScheduled());
    }

    /**
     * Миграция разрешения сохраняется: ключа выбора нет → boot назначает
     * одноразовый будильник повторной проверки; после подтверждённой
     * миграции его срабатывание запускает сервис.
     */
    @Test
    public void migrationPendingStillSchedulesConfirmationRetry()
            throws Exception {
        CredHelper.setToken(context, "SYNTH_TOKEN_A");
        CredHelper.setChatId(context, 777);
        prefs.edit()
                .remove(VkSenderService.PREF_TRANSMISSION_ENABLED).commit();
        VkSenderService.resetTransmissionFlagsForTest();

        fireBoot();
        VkSenderService.awaitSwitchIdleForTest();

        assertTrue("миграция должна подтвердить прежнее разрешение",
                VkSenderService.isTransmissionAllowed(context));
        assertEquals("повторная проверка не назначена",
                1, retryAlarmsScheduled());

        // Срабатывание назначенной проверки запускает сервис, когда
        // выбор уже подтверждён.
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager.ScheduledAlarm a =
                shadowOf(am).getNextScheduledAlarm();
        Intent saved = shadowOf(a.operation).getSavedIntent();
        assertTrue(saved.getBooleanExtra("confirm_retry", false));
        new AlarmReceiver().onReceive(context, saved);

        Intent svc = nextStarted();
        assertNotNull(svc);
        assertEquals(VkSenderService.class.getName(),
                svc.getComponent().getClassName());
    }
}
