package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BgReceiver как вход недоверенного сигнала: extras не читаются, сервису
 * передаётся только флаг xdrip_hint. Прямой вызов onReceive под Robolectric
 * НЕ моделирует системную проверку полномочий между приложениями — показана
 * только обработка содержимого/маршрутизация и ограничение частоты.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BgReceiverTest {

    private Context context;
    private SharedPreferences prefs;
    private AtomicLong mono;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        // передача разрешена: тестируется обработка broadcast, а не запрет
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true)
                .commit();
        mono = new AtomicLong(0);
        VkSenderService.hintClockOverride = mono::get;
        VkSenderService.resetTransmissionFlagsForTest();
        VkSenderService.hintClockOverride = mono::get;
    }

    @After
    public void tearDown() {
        VkSenderService.hintClockOverride = null;
    }

    private Intent xdripBroadcast() {
        Intent i = new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE);
        // extras намеренно поддельные: свежее время, значение 999, чужой тренд
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 999);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() + 60_000);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName", "FORGED");
        i.putExtra("url", "http://169.254.1.1/"); // попытка перенастроить endpoint
        return i;
    }

    private ShadowApplication shadowApp() {
        return shadowOf((Application) context);
    }

    private Intent nextStarted() {
        return shadowApp().getNextStartedService();
    }

    @Test
    public void officialActionTriggersHintOnly() {
        new BgReceiver().onReceive(context, xdripBroadcast());

        Intent svc = nextStarted();
        assertNotNull("официальный action должен дойти до команды сервису", svc);
        assertTrue(svc.getBooleanExtra("xdrip_hint", false));
        // Extras broadcast не переносятся и никуда не пишутся
        assertNull(svc.getExtras() == null ? null
                : svc.getExtras().get("bg_value"));
        assertNull(svc.getExtras() == null ? null
                : svc.getExtras().get("timestamp"));
        assertNull(svc.getExtras() == null ? null
                : svc.getExtras().get("direction"));
        assertFalse(svc.getBooleanExtra("auto_update", false));
        assertFalse(svc.getBooleanExtra("manual_send", false));
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals(0f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
    }

    @Test
    public void forgedExtrasReachNeitherCacheNorService() {
        new BgReceiver().onReceive(context, xdripBroadcast());
        Intent svc = nextStarted();
        assertNotNull(svc);
        // ровно один внешний extra — флаг сигнала
        assertEquals(1, svc.getExtras().size());
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
    }

    @Test
    public void foreignNullAndMissingActionRejected() {
        // null intent
        new BgReceiver().onReceive(context, null);
        // action отсутствует
        new BgReceiver().onReceive(context, new Intent());
        // посторонний action с корректными extras
        Intent foreign = new Intent("com.foreign.action.X");
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 120);
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() - 60_000);
        new BgReceiver().onReceive(context, foreign);
        // другой официальный action xDrip (StatusUpdate) — не обрабатывается
        new BgReceiver().onReceive(context,
                new Intent("com.eveningoutpost.dexdrip.StatusUpdate"));

        assertNull("нерелевантные входы не запускают сервис", nextStarted());
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
    }

    @Test
    public void signalBurstRateLimitedAndCoalesced() {
        // Серия из 100 сигналов — один запуск сервиса.
        for (int i = 0; i < 100; i++) {
            new BgReceiver().onReceive(context, xdripBroadcast());
        }
        Intent first = nextStarted();
        assertNotNull(first);
        assertNull("повторные сигналы объединены", nextStarted());

        // Пока обработка в полёте (inFlight), окно не ограничивает объединение.
        mono.addAndGet(60_000);
        new BgReceiver().onReceive(context, xdripBroadcast());
        assertNull("сигнал во время обработки объединён", nextStarted());
    }

    @Test
    public void nextSignalAllowedAfterInterval() {
        new BgReceiver().onReceive(context, xdripBroadcast());
        assertNotNull(nextStarted());

        // Сервис взял сигнал в обработку — in-flight снят (модель).
        VkSenderService.consumeHintForTest();

        mono.addAndGet(16_000); // > 15 с с последнего принятого
        new BgReceiver().onReceive(context, xdripBroadcast());
        assertNotNull("сигнал после окна допускается", nextStarted());
    }

    @Test
    public void deniedTransmissionRejectsAllInputs() {
        // Сохранённый запрет передачи закрывает все входы: broadcast,
        // explicit intent, повтор — ни кандидата, ни запуска сервиса.
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false)
                .commit();
        VkSenderService.resetTransmissionFlagsForTest();
        VkSenderService.hintClockOverride = mono::get;
        new BgReceiver().onReceive(context, xdripBroadcast());
        Intent foreign = new Intent("com.foreign.action.X");
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 110);
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() - 60_000);
        new BgReceiver().onReceive(context, foreign);
        new BootReceiver().onReceive(context,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
        new AlarmReceiver().onReceive(context, new Intent());

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertNull(nextStarted());
    }
}
