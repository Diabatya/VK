package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.max.MaxChannel;
import com.example.xdrip2vk.max.MaxNotificationListener;
import com.example.xdrip2vk.max.MaxSender;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * MAX-1: интеграция канала MAX с общим пайплайном VkSenderService.
 * Настоящий сервис + слушатель под Robolectric; подменены только границы:
 * VK-отправка (senderOverride), команда в MAX (sendCommandForTest),
 * пакет-создатель кнопки (creatorPackageForTest), часы (clockForTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaxServiceIntegrationTest {

    private Context context;
    private SharedPreferences prefs;
    private MaxNotificationListener listener;
    private List<String> vkTexts;
    private List<Intent> maxFillIns;
    private AtomicLong clock;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        context.getSharedPreferences("max_channel", Context.MODE_PRIVATE)
                .edit().clear().commit();
        prefs.edit()
                .putBoolean(VkSenderService.PREF_TRANSMISSION_ENABLED, true)
                .putBoolean(MaxChannel.PREF_MAX_ENABLED, true)
                .commit();
        VkSenderService.resetTransmissionFlagsForTest();
        MaxSender.resetRuntimeForTest(context);
        // Детерминизм: события MAX исполняются синхронно на вызывающем потоке.
        MaxSender.workerForTest = Runnable::run;
        MaxChannel.retrySchedulerForTest = (task, delayMs) -> task.run();

        // Доступ слушателя уведомлений выдан — R4 требует его для отправки.
        android.provider.Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners",
                new android.content.ComponentName(context,
                        MaxNotificationListener.class).flattenToString());

        listener = Robolectric.buildService(MaxNotificationListener.class).create().get();
        vkTexts = new ArrayList<>();
        maxFillIns = new ArrayList<>();
        VkSenderService.senderOverride = (peer, text, rid) -> {
            vkTexts.add(text);
            return com.example.xdrip2vk.core.SendResult.SENT;
        };
        VkSenderService.identityVerifierOverride = token -> "user:1";
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> maxFillIns.add(fillIn);
        MaxSender.creatorPackageForTest = pi -> MaxChannel.MAX_PACKAGE;
        clock = new AtomicLong(System.currentTimeMillis());
        MaxSender.clockForTest = clock::get;
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        MaxSender.sendCommandForTest = null;
        MaxSender.creatorPackageForTest = null;
        MaxSender.clockForTest = null;
        MaxSender.workerForTest = null;
        MaxChannel.retrySchedulerForTest = null;
        MaxChannel.storeFactoryForTest = null;
        VkSenderService.resetTransmissionFlagsForTest();
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
        i.putExtra("direction", "SingleUp");
        return i;
    }

    private void selectMaxChat() {
        PendingIntent pi = PendingIntent.getBroadcast(context, 42,
                new Intent("com.example.xdrip2vk.test.MAX_PI"),
                PendingIntent.FLAG_IMMUTABLE);
        Bundle extras = new Bundle();
        extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Папа");
        Notification.Action reply = new Notification.Action.Builder(
                null, "Ответить", pi)
                .addRemoteInput(new RemoteInput.Builder("key_text")
                        .setLabel("Ответить").build())
                .build();
        Notification n = new Notification.Builder(context, "ru.oneme.app.dialogs")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Папа")
                .setExtras(extras)
                .addAction(reply)
                .build();
        listener.onNotificationPosted(new StatusBarNotification(
                MaxChannel.MAX_PACKAGE, MaxChannel.MAX_PACKAGE, 378, null, 0, 0, 0,
                n, Process.myUserHandle(), clock.get()));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
    }

    private String fillInText(Intent fillIn) {
        Bundle results = RemoteInput.getResultsFromIntent(fillIn);
        CharSequence cs = results == null ? null : results.getCharSequence("key_text");
        return cs == null ? null : cs.toString();
    }

    @Test
    public void bothChannelsGetSameAcceptedReading() {
        CredHelper.setToken(context, "SYNTHETIC_TOKEN");
        CredHelper.setChatId(context, 777);
        selectMaxChat();
        VkSenderService s = startService();

        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, vkTexts.size());
        assertEquals(1, maxFillIns.size());
        // Общий форматтер: текст в VK и в MAX — одна строка.
        assertEquals(vkTexts.get(0), fillInText(maxFillIns.get(0)));
        s.onDestroy();
    }

    @Test
    public void maxWorksWithoutVkCredentials() {
        // Токена нет вообще — VK-канал не сконфигурирован.
        selectMaxChat();
        VkSenderService s = startService();

        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(0, vkTexts.size()); // ставить в VK нечем и нечего
        assertEquals(1, maxFillIns.size()); // MAX работает без реквизитов VK
        s.onDestroy();
    }

    @Test
    public void vkWorksWithMaxDisabled() {
        CredHelper.setToken(context, "SYNTHETIC_TOKEN");
        CredHelper.setChatId(context, 777);
        prefs.edit().putBoolean(MaxChannel.PREF_MAX_ENABLED, false).commit();
        VkSenderService s = startService();

        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, vkTexts.size());
        assertEquals(0, maxFillIns.size());
        s.onDestroy();
    }

    @Test
    public void vkOffKeepsMaxFlow() {
        CredHelper.setToken(context, "SYNTHETIC_TOKEN");
        CredHelper.setChatId(context, 777);
        selectMaxChat();
        prefs.edit().putBoolean(MaxChannel.PREF_VK_ENABLED, false).commit();
        VkSenderService s = startService();

        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(0, vkTexts.size()); // VK-канал выключен
        assertEquals(1, maxFillIns.size()); // MAX получает то же показание
        s.onDestroy();
    }

    @Test
    public void realStopBlocksMaxSendAndNotification() {
        selectMaxChat();
        VkSenderService s = startService();
        // Настоящий общий Stop: процессный запрет ставится синхронно,
        // подтверждённая запись — асинхронно на switchExecutor; ждём её
        // завершения latch'ем, чтобы запись не уехала в следующий тест.
        java.util.concurrent.CountDownLatch stopDone =
                new java.util.concurrent.CountDownLatch(1);
        VkSenderService.requestStopTransmission(context, ok -> stopDone.countDown());

        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(0, maxFillIns.size()); // автоотправка в MAX запрещена

        // Уведомление после Stop обновляет состояние чата, но передачу не включает.
        selectMaxChat(); // повторная кнопка «Ответить» из нового уведомления
        MaxSender.onReading(context, "6.6", clock.get());
        assertEquals(0, maxFillIns.size());
        s.onDestroy();
        try {
            assertTrue(stopDone.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("ожидание записи Stop прервано");
        }
    }

    @Test
    public void duplicateReadingDoesNotRepeatMaxCommand() {
        selectMaxChat();
        VkSenderService s = startService();
        long ts = System.currentTimeMillis() - 60_000;
        s.onStartCommand(autoUpdate(120, ts), 0, 1);
        idle(s);
        assertEquals(1, maxFillIns.size());
        // То же показание второй раз (повтор broadcast/опроса) — команде не повторять.
        s.onStartCommand(autoUpdate(120, ts), 0, 2);
        idle(s);
        assertEquals(1, maxFillIns.size());
        s.onDestroy();
    }
}
