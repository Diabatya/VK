package com.example.xdrip2vk.max;

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

import com.example.xdrip2vk.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MAX-1: канал передачи показаний в чат MAX кнопкой «Ответить».
 * Настоящий MaxNotificationListener + MaxSender + MaxChannel под Robolectric;
 * подменяются только границы: PendingIntent.send (sendCommandForTest) и
 * пакет-создатель кнопки (creatorPackageForTest). Без sleep — вызовы
 * синхронные, состояние процесса сбрасывается явно.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaxChannelTest {

    private Context context;
    private SharedPreferences prefs;
    private SharedPreferences maxPrefs;
    private MaxNotificationListener listener;
    private List<PendingIntent> sentCommands;
    private List<Intent> sentFillIns;
    private AtomicLong clock;
    private int piSeq;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        maxPrefs = context.getSharedPreferences("max_channel", Context.MODE_PRIVATE);
        maxPrefs.edit().clear().commit();
        prefs.edit()
                .putBoolean("transmission_enabled", true)
                .putBoolean(MaxChannel.PREF_MAX_ENABLED, true)
                .commit();
        MaxState.resetForTest();

        listener = Robolectric.buildService(MaxNotificationListener.class).create().get();

        sentCommands = new ArrayList<>();
        sentFillIns = new ArrayList<>();
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            sentCommands.add(pi);
            sentFillIns.add(fillIn);
        };
        MaxSender.creatorPackageForTest = pi -> MaxChannel.MAX_PACKAGE;
        clock = new AtomicLong(1_700_000_000_000L);
        MaxSender.clockForTest = clock::get;
        piSeq = 0;

        // В тестовом окружении MAX не установлен — регистрируем фиктивный
        // пакет, иначе statusText отдаёт «не установлен» раньше нужных веток.
        android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
        pi.packageName = MaxChannel.MAX_PACKAGE;
        pi.versionName = "1.0";
        pi.versionCode = 1;
        org.robolectric.Shadows.shadowOf(context.getPackageManager())
                .installPackage(pi);

        // Доступ слушателя уведомлений выдан (системная настройка).
        android.provider.Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners",
                new android.content.ComponentName(context,
                        MaxNotificationListener.class).flattenToString());
    }

    @After
    public void tearDown() {
        MaxSender.sendCommandForTest = null;
        MaxSender.creatorPackageForTest = null;
        MaxSender.clockForTest = null;
        MaxState.resetForTest();
    }

    // --- фабрики ---------------------------------------------------------

    private PendingIntent fakePi() {
        piSeq++;
        return PendingIntent.getBroadcast(context, 1000 + piSeq,
                new Intent("com.example.xdrip2vk.test.PI_" + piSeq),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private StatusBarNotification maxSbn(String chat, String channel,
                                         int notifId, PendingIntent pi) {
        Bundle extras = new Bundle();
        extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, chat);
        Notification.Action reply = new Notification.Action.Builder(
                null, "Ответить", pi)
                .addRemoteInput(new RemoteInput.Builder("key_text")
                        .setLabel("Ответить").build())
                .build();
        Notification n = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(chat)
                .setExtras(extras)
                .addAction(reply)
                .build();
        return new StatusBarNotification(MaxChannel.MAX_PACKAGE,
                MaxChannel.MAX_PACKAGE, notifId, null, 0, 0, 0, n,
                Process.myUserHandle(), clock.get());
    }

    /** Уведомление чата «Папа» с кнопкой ответа (ещё не выбранного). */
    private PendingIntent captureTarget() {
        PendingIntent pi = fakePi();
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, pi));
        assertNull(MaxState.targetButton); // без выбора цель не назначается
        return pi;
    }

    private void selectPapa(PendingIntent pi) {
        // Явный выбор пользователя: кандидат из списка слушателя.
        java.util.List<String> labels = MaxSender.candidateLabels();
        assertEquals(1, labels.size());
        assertTrue(MaxSender.selectCandidate(context, 0));
        assertNotNull(MaxChannel.getTarget(context));
    }

    private String fillInText(Intent fillIn) {
        Bundle results = RemoteInput.getResultsFromIntent(fillIn);
        CharSequence cs = results == null ? null : results.getCharSequence("key_text");
        return cs == null ? null : cs.toString();
    }

    // --- обязательные проверки --------------------------------------------

    @Test
    public void maxDisabledByDefaultAfterUpgrade() {
        // Чистый prefs (как после обновления существующей установки):
        // VK сохраняет прежнее поведение, MAX выключен.
        prefs.edit().remove(MaxChannel.PREF_VK_ENABLED)
                .remove(MaxChannel.PREF_MAX_ENABLED).commit();
        assertTrue(MaxChannel.isVkEnabled(context));
        assertFalse(MaxChannel.isMaxEnabled(context));
        // MAX выключен → показание не ставится и не отправляется.
        captureTarget();
        selectPapa(null);
        MaxSender.onReading(context, "6.1 ↗", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void acceptedReadingSentToMax() {
        captureTarget();
        selectPapa(null);
        MaxSender.onReading(context, "6.1 ↗ 12:00", clock.get());
        assertEquals(1, sentCommands.size());
        assertEquals("6.1 ↗ 12:00", fillInText(sentFillIns.get(0)));
        // Передача команды ≠ доставка.
        String status = MaxSender.statusText(context);
        assertTrue(status, status.contains("Передано в MAX"));
        assertFalse(status, status.contains("Доставлено"));
    }

    @Test
    public void repeatOfSameReadingDoesNotCreateSecondCommand() {
        captureTarget();
        selectPapa(null);
        long ts = clock.get();
        MaxSender.onReading(context, "6.1", ts);
        assertEquals(1, sentCommands.size());
        MaxSender.onReading(context, "6.1", ts); // тот же reading ts — повтор запрещён
        assertEquals(1, sentCommands.size());
    }

    @Test
    public void foreignPackageCannotBecomeCandidate() {
        // Уведомление «от MAX», но пакет другого приложения — игнорируется.
        StatusBarNotification foreign = maxSbn("Папа", "x", 1, fakePi());
        // Подделываем пакет через новый объект.
        foreign = new StatusBarNotification("com.evil.app", "com.evil.app", 1,
                null, 0, 0, 0, foreign.getNotification(), Process.myUserHandle(),
                clock.get());
        listener.onNotificationPosted(foreign);
        assertEquals(0, MaxSender.candidateLabels().size());
        assertNull(MaxState.targetButton);
    }

    @Test
    public void differentChatDoesNotOverrideTarget() {
        PendingIntent piPapa = fakePi();
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, piPapa));
        selectPapa(null);
        // Уведомление другого чата — другой чат не становится адресатом.
        listener.onNotificationPosted(
                maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        assertSame(piPapa, MaxState.targetButton.pendingIntent);
        MaxSender.onReading(context, "6.2", clock.get());
        assertEquals(1, sentCommands.size());
        assertSame(piPapa, sentCommands.get(0));
    }

    @Test
    public void sameNameChatsStayDistinct() {
        // Два чата с одинаковым названием: личный и группа — разные кандидаты.
        PendingIntent piDialog = fakePi();
        PendingIntent piGroup = fakePi();
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, piDialog));
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.chats", 900, piGroup));
        java.util.List<String> labels = MaxSender.candidateLabels();
        assertEquals(2, labels.size());
        // Выбираем группу (свежий кандидат первый).
        assertTrue(MaxSender.selectCandidate(context, 0));
        assertSame(piGroup, MaxState.targetButton.pendingIntent);
        // Уведомление личного «Папа» не подменяет группу.
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertSame(piGroup, MaxState.targetButton.pendingIntent);
    }

    @Test
    public void sharedPendingIntentBlocksSending() {
        PendingIntent shared = fakePi();
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, shared));
        selectPapa(null);
        // Другой чат прислал ТОТ ЖЕ PendingIntent — ответ ушёл бы не туда.
        listener.onNotificationPosted(
                maxSbn("Мама", "ru.oneme.app.dialogs", 555, shared));
        assertEquals("Мама", MaxChannel.getBlockedBy(context));
        MaxSender.onReading(context, "6.3", clock.get());
        assertEquals(0, sentCommands.size());
        // Новое уведомление именно выбранного чата снимает блокировку.
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertNull(MaxChannel.getBlockedBy(context));
        assertEquals(1, sentCommands.size()); // отложенное показание ушло
    }

    @Test
    public void canceledButtonShowsNeedNewMessage() {
        captureTarget();
        selectPapa(null);
        // MAX отменил кнопку: PendingIntent.send бросает CanceledException.
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            throw new PendingIntent.CanceledException();
        };
        MaxSender.onReading(context, "6.4", clock.get());
        assertNull(MaxState.targetButton);
        String status = MaxSender.statusText(context);
        assertTrue(status, status.contains("Нужно новое сообщение"));
    }

    @Test
    public void missingButtonShowsNeedNewMessage() {
        captureTarget();
        selectPapa(null);
        // Кнопка утрачена полностью: память и сохранённая копия (backup-уведомление).
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.5", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("Нужно новое сообщение"));
    }

    @Test
    public void stopBlocksSendAndLaterNotification() {
        captureTarget();
        selectPapa(null);
        // Модель Stop — выключенное подтверждённое разрешение (реальный
        // requestStopTransmission покрыт в MaxServiceIntegrationTest).
        prefs.edit().putBoolean("transmission_enabled", false).commit();
        MaxSender.onReading(context, "6.6", clock.get());
        assertEquals(0, sentCommands.size());
        // Уведомление после Stop обновляет кнопку, но передачу не включает.
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertNotNull(MaxState.targetButton);
        assertEquals(0, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("остановлена"));
    }

    @Test
    public void stalePendingReadingNotSent() {
        captureTarget();
        selectPapa(null);
        MaxState.targetButton = null; // кнопка потеряна — показание ждёт
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(0, sentCommands.size());
        // Кнопка пришла через 20 минут — показание старше 15 мин не уходит.
        clock.addAndGet(20 * 60_000L);
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(0, sentCommands.size());
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void newChatDoesNotReceiveOldPending() {
        captureTarget();
        selectPapa(null);
        MaxState.targetButton = null; // нет кнопки — показание копится
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.8", clock.get());
        assertNotNull(MaxChannel.getPending(context));
        // Выбрали другой чат — старое ожидающее ему не достаётся.
        listener.onNotificationPosted(
                maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        assertTrue(MaxSender.selectCandidate(context, 0));
        assertNull(MaxChannel.getPending(context));
        MaxSender.onButtonCaptured(context);
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void foreignCreatorPackageRejected() {
        captureTarget();
        selectPapa(null);
        // Кнопка «не от MAX» — команда не передаётся.
        MaxSender.creatorPackageForTest = pi -> "com.evil.app";
        MaxSender.onReading(context, "6.9", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void testMessageMarkedTestAndSends() {
        captureTarget();
        selectPapa(null);
        String result = MaxSender.sendTest(context);
        assertEquals(1, sentCommands.size());
        String text = fillInText(sentFillIns.get(0));
        assertTrue(text, text.startsWith("Тест"));
        assertTrue(result, result.contains("не подтверждена"));
        // Успешный тест не ставит показание в ожидание.
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void pendingFlushesOnFreshButton() {
        captureTarget();
        selectPapa(null);
        MaxState.targetButton = null; // кнопка потеряна, показание ждёт
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "7.0", clock.get());
        assertEquals(0, sentCommands.size());
        // Новое уведомление выбранного чата за 5 минут — показание свежее.
        clock.addAndGet(5 * 60_000L);
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertEquals("7.0", fillInText(sentFillIns.get(0)));
    }
}
