package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.SendResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * №2Б.1: планировщик повторов при auth-блоке и достоверный статус доставки.
 * Среда: Robolectric 4.13 (sdk=34), VK-отправка через senderOverride.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceDeliveryTest {

    private Context context;
    private SharedPreferences prefs;
    private java.util.List<String> peers;
    private AtomicReference<SendResult> result;
    private java.util.function.Function<String, SendResult> rule;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN");
        TokenStorage.setChatId(context, 777);
        peers = new java.util.ArrayList<>();
        result = new AtomicReference<>(SendResult.SENT);
        rule = null;
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            return rule != null ? rule.apply(peer) : result.get();
        };
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class).create().get();
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
        i.putExtra("direction", "SingleDown");
        return i;
    }

    private String lastNotificationText(VkSenderService s) {
        CharSequence t = s.notificationForTest();
        return t == null ? "" : t.toString();
    }

    // --- планировщик: нет посекундного цикла при auth-блоке ---

    @Test
    public void authBlockDoesNotRescheduleEverySecond() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();

        // обычное сообщение получает AUTH_FAILED, тревога остаётся в очереди
        rule = (peer) -> peer.equals("777") ? SendResult.AUTH_FAILED : SendResult.SENT;
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, peers.size()); // тревога не пыталась отправиться после auth-блока
        // следующее пробуждение — по сроку истечения (~15 мин), не через секунду
        assertTrue("посекундный цикл: delay=" + s.lastDrainDelayMs,
                s.lastDrainDelayMs > 60_000);

        // повторный приход данных с тем же токеном — сетевых попыток нет,
        // цикл не перезапускается
        s.onStartCommand(autoUpdate(60, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s);
        assertEquals(1, peers.size());
        assertTrue(s.lastDrainDelayMs > 60_000);
        s.onDestroy();
    }

    // --- статус доставки ---

    @Test
    public void permanentErrorShowsPartialFailureNotRetryPromise() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();

        // обычное доставлено, тревога — окончательная ошибка
        rule = (peer) -> peer.equals("888") ? SendResult.PERMANENT : SendResult.SENT;
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        String text = lastNotificationText(s);
        assertTrue("ожидался статус частичной ошибки, был: " + text,
                text.contains("не доставлен"));
        assertFalse("обещания повтора быть не должно: " + text,
                text.contains("повтор через"));

        // следующий успешный обычный пакет не скрывает актуальную ошибку тревоги
        rule = (peer) -> SendResult.SENT;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s);

        text = lastNotificationText(s);
        assertTrue("ошибка тревоги скрыта успехом: " + text,
                text.contains("не доставлен"));
        s.onDestroy();
    }

    @Test
    public void retryableShowsRealScheduledDelay() {
        VkSenderService s = startService();
        result.set(SendResult.RETRYABLE);
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        String text = lastNotificationText(s);
        assertTrue("ожидался повтор с реальной задержкой, был: " + text,
                text.contains("повтор через"));
        s.onDestroy();
    }

    @Test
    public void alertWithoutSelectedChatShowsStatusButRegularSent() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        // тревожный чат НЕ выбран (alertChatId = 0)
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        // обычное сообщение уходит, тревога не создана для peer 0
        assertEquals(1, peers.size());
        assertEquals("777", peers.get(0));
        String text = lastNotificationText(s);
        assertTrue("ожидался статус про невыбранный чат тревог, был: " + text,
                text.contains("не выбран чат"));
        s.onDestroy();
    }
}
