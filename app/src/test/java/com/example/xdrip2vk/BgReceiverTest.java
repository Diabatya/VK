package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/**
 * Тесты реального входного пути: BgReceiver → SharedPreferences → Intent сервиса.
 * Синтетические данные, без реальных медицинских значений.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BgReceiverTest {

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        // передача разрешена: тестируется обработка broadcast, а не запрет
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true)
                .commit();
        VkSenderService.resetTransmissionFlagsForTest();
    }

    private Intent xdripBroadcast(double bg, long ts, String slopeName) {
        Intent i = new Intent("com.eveningoutpost.dexdrip.BgEstimate");
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", bg);
        if (ts != 0) {
            i.putExtra("com.eveningoutpost.dexdrip.Extras.Time", ts);
        }
        if (slopeName != null) {
            i.putExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName", slopeName);
        }
        return i;
    }

    private ShadowApplication shadowApp() {
        return shadowOf((Application) context);
    }

    @Test
    public void freshBroadcastPublishedAsCandidateNotUiCache() {
        long ts = System.currentTimeMillis() - 60_000;
        new BgReceiver().onReceive(context, xdripBroadcast(120, ts, "SingleDown"));

        // входной кеш-кандидат заполнен
        assertEquals(120f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        assertEquals(ts, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals("SingleDown", prefs.getString("candidate_bg_direction", ""));

        // доверенный UI-кеш receiver не трогает — его пишет только сервис
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
        assertEquals(0f, prefs.getFloat("last_bg_value", 0), 0.01f);

        // сервис получает неизменяемый снимок, включая официальный тренд
        Intent svc = shadowApp().getNextStartedService();
        assertNotNull(svc);
        assertTrue(svc.getBooleanExtra("auto_update", false));
        assertFalse(svc.getBooleanExtra("manual_send", false));
        assertEquals(120.0, svc.getDoubleExtra("bg_value", 0), 0.01);
        assertEquals(ts, svc.getLongExtra("timestamp", 0));
        assertEquals("SingleDown", svc.getStringExtra("direction"));
    }

    @Test
    public void futureBroadcastRejectedNotCached() {
        long tomorrow = System.currentTimeMillis() + 24L * 3600 * 1000;
        new BgReceiver().onReceive(context, xdripBroadcast(120, tomorrow, "Flat"));

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void staleBroadcastRejected() {
        long stale = System.currentTimeMillis() - 20L * 60 * 1000;
        new BgReceiver().onReceive(context, xdripBroadcast(120, stale, "Flat"));

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void missingTimestampBroadcastRejected() {
        new BgReceiver().onReceive(context, xdripBroadcast(120, 0, "Flat"));

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void invalidBgBroadcastRejected() {
        long ts = System.currentTimeMillis() - 60_000;
        // NaN, ±Infinity, ноль, отрицательное и конечный double, переполняющий float
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0, -5, 1e40}) {
            new BgReceiver().onReceive(context, xdripBroadcast(bad, ts, "Flat"));
        }

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void floatExtraTypeHandled() {
        long ts = System.currentTimeMillis() - 60_000;
        Intent i = new Intent("com.eveningoutpost.dexdrip.BgEstimate");
        i.putExtra("bg_value", (float) 120);
        i.putExtra("timestamp", ts);
        new BgReceiver().onReceive(context, i);

        assertEquals(120f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        assertEquals(120.0,
                shadowApp().getNextStartedService()
                        .getDoubleExtra("bg_value", 0), 0.01);
    }

    @Test
    public void newerReadingSurvivesLateOlderBroadcast() {
        long now = System.currentTimeMillis();
        // UI-кеш уже содержит новое принятое показание (записал сервис)
        prefs.edit()
                .putFloat("last_bg_value", 130f)
                .putLong("last_bg_timestamp", now - 60_000)
                .putString("last_bg_direction", "Flat")
                .apply();

        // запоздалый broadcast со старым временем
        new BgReceiver().onReceive(context, xdripBroadcast(110, now - 5 * 60_000, "SingleUp"));

        // UI-кеш не затёрт
        assertEquals(130f, prefs.getFloat("last_bg_value", 0), 0.01f);
        assertEquals(now - 60_000, prefs.getLong("last_bg_timestamp", 0));
        assertEquals("Flat", prefs.getString("last_bg_direction", ""));
        // старый пришёл лишь во входной кандидат — сервис отсечёт его дедупом
        assertEquals(now - 5 * 60_000, prefs.getLong("candidate_bg_timestamp", 0));
    }

    // --- Текущее поведение доверия (задание 2В.1): подтверждение отправителя
    // отсутствует — тесты документируют уязвимость, а не одобряют её.
    // Прямой вызов onReceive под Robolectric не моделирует системную проверку
    // полномочий между приложениями; он показывает лишь, что содержимое
    // намерения определяет обработку независимо от его происхождения.

    @Test
    public void forgedBroadcastWithSameContractAccepted() {
        // «Посторонний отправитель»: содержимое соответствует официальному
        // контракту — receiver не проверяет и не может проверить отправителя.
        long ts = System.currentTimeMillis() - 60_000;
        new BgReceiver().onReceive(context, xdripBroadcast(100, ts, "Flat"));

        assertEquals(100f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        assertNotNull("контент корректен — принят независимо от отправителя",
                shadowApp().getNextStartedService());
    }

    @Test
    public void explicitIntentWithForeignActionStillProcessed() {
        // Explicit Intent к exported receiver обходит intent-filter, а сам
        // receiver action не проверяет: чужой/отсутствующий action с
        // корректными extras обрабатывается так же.
        long ts = System.currentTimeMillis() - 60_000;
        for (String action : new String[]{"com.foreign.action.X", null}) {
            prefs.edit().remove("candidate_bg_timestamp")
                    .remove("candidate_bg_value").commit();
            shadowApp().clearStartedServices();
            Intent i = new Intent(action);
            i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 110);
            i.putExtra("com.eveningoutpost.dexdrip.Extras.Time", ts);
            new BgReceiver().onReceive(context, i);
            assertEquals("action=" + action, 110f,
                    prefs.getFloat("candidate_bg_value", 0), 0.01f);
            assertNotNull("action=" + action,
                    shadowApp().getNextStartedService());
        }
    }

    @Test
    public void invalidExtraTypesRejected() {
        // Некорректные типы: строка-не-число и объект не дают показания.
        long ts = System.currentTimeMillis() - 60_000;
        Intent i = new Intent("com.eveningoutpost.dexdrip.BgEstimate");
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", "abc");
        i.putExtra("estimate", new java.util.Date());
        i.putExtra("bg_value", "12x");
        i.putExtra("com.eveningoutpost.dexdrip.Extras.Time", ts);
        new BgReceiver().onReceive(context, i);

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }

    @Test
    public void duplicateBroadcastRepublishedDedupByService() {
        // Повтор одного показания: receiver публикует кандидата повторно
        // (receiver — входной фильтр, дедупликация показаний — в сервисе).
        long ts = System.currentTimeMillis() - 60_000;
        new BgReceiver().onReceive(context, xdripBroadcast(120, ts, "Flat"));
        new BgReceiver().onReceive(context, xdripBroadcast(120, ts, "Flat"));
        assertEquals(120f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        assertNotNull(shadowApp().getNextStartedService());
        assertNotNull(shadowApp().getNextStartedService());
    }

    @Test
    public void deniedTransmissionRejectsAllInputs() {
        // Сохранённый запрет передачи закрывает все входы: broadcast,
        // explicit intent, повтор — ни кандидата, ни запуска сервиса.
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false)
                .commit();
        VkSenderService.resetTransmissionFlagsForTest();
        long ts = System.currentTimeMillis() - 60_000;
        new BgReceiver().onReceive(context, xdripBroadcast(120, ts, "Flat"));
        Intent foreign = new Intent("com.foreign.action.X");
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 110);
        foreign.putExtra("com.eveningoutpost.dexdrip.Extras.Time", ts);
        new BgReceiver().onReceive(context, foreign);
        new BootReceiver().onReceive(context,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
        new AlarmReceiver().onReceive(context, new Intent());

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertNull(shadowApp().getNextStartedService());
    }
}
