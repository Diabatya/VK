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
        new BgReceiver().onReceive(context, xdripBroadcast(Double.NaN, ts, "Flat"));
        new BgReceiver().onReceive(context, xdripBroadcast(-5, ts, "Flat"));

        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
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
}
