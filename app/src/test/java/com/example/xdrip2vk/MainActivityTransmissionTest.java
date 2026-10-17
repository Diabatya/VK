package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/**
 * №2Б.4: кнопки «Запустить»/«Остановить», ручная отправка и устаревший
 * callback запроса разрешений. Сеть не используется.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTransmissionTest {

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        VkSenderService.resetTransmissionFlagsForTest();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_1");
        TokenStorage.setChatId(context, 777);
    }

    @After
    public void tearDown() {
        VkSenderService.transmissionWriteOverride = null;
        VkSenderService.resetTransmissionFlagsForTest();
    }

    private ShadowApplication shadowApp() {
        return shadowOf((Application) context);
    }

    private MainActivity activity() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private String chipText(MainActivity a) {
        return ((com.google.android.material.chip.Chip) a.findViewById(R.id.chip_status))
                .getText().toString();
    }

    @Test
    public void manualSendBlockedWhenStopped() {
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, false)
                // свежее показание в UI-кеш — иначе статус был бы «нет данных»
                .putFloat("last_bg_value", 120f)
                .putLong("last_bg_timestamp", System.currentTimeMillis() - 60_000)
                .commit();
        MainActivity a = activity();

        a.findViewById(R.id.fab_send_now).performClick();

        assertEquals("Сначала запустите передачу", chipText(a));
        assertNull("ручная отправка не запускает сервис при запрете",
                shadowApp().getNextStartedService());
    }

    @Test
    public void stalePermissionGrantAfterStopDoesNotStart() {
        shadowApp().denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        MainActivity a = activity();

        // «Запустить» → уходит в запрос разрешения, сервис ещё не стартует
        a.findViewById(R.id.btn_start_service).performClick();
        assertNull(shadowApp().getNextStartedService());

        // пока диалог висит, пользователь нажал «Остановить»
        a.findViewById(R.id.btn_stop_service).performClick();
        assertFalse(VkSenderService.isTransmissionAllowed(context));

        // устаревший callback с grant — передачу не включает
        a.onRequestPermissionsResult(100,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});

        assertNull("поздний grant после остановки не запускает сервис",
                shadowApp().getNextStartedService());
        assertFalse(VkSenderService.isTransmissionAllowed(context));
        assertEquals("Передача остановлена", chipText(a));
    }

    @Test
    public void statusDistinguishesUserStopFromDeadProcess() {
        MainActivity a = activity();
        // передача разрешена, но процесс не живёт (авария/выгрузка) —
        // это не пользовательское «Остановлено»
        assertFalse(VkSenderService.isRunning());
        a.onResume();
        assertEquals("Передача включена — сервис не активен", chipText(a));

        VkSenderService.requestStopTransmission(context);
        a.onResume();
        assertEquals("Передача остановлена", chipText(a));
    }

    @Test
    public void stopButtonHonestOnWriteFailure() {
        VkSenderService.transmissionWriteOverride = (enabled, markBaselinePending) -> false;
        MainActivity a = activity();
        a.findViewById(R.id.btn_stop_service).performClick();
        // передача в процессе заблокирована, а статус — «остановлено»
        assertFalse(VkSenderService.isTransmissionAllowed(context));
        assertEquals("Передача остановлена", chipText(a));
    }
}
