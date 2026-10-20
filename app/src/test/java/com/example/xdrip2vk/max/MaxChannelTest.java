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
        MaxChannel.resetForTest();
        MaxState.listenerConnected = true; // слушатель в тесте считается подключённым

        // Детерминизм: события исполняются на вызывающем потоке, повторы
        // записей — немедленно. Реальный однопоточный исполнитель процесса
        // проверяется отдельным тестом неблокируемости.
        MaxSender.workerForTest = Runnable::run;
        MaxChannel.retrySchedulerForTest = (task, delayMs) -> task.run();

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
        MaxSender.beforeCommandHookForTest = null;
        MaxSender.workerForTest = null;
        MaxChannel.retrySchedulerForTest = null;
        MaxChannel.storeFactoryForTest = null;
        MaxChannel.resetForTest();
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
        // Явный выбор пользователя: кандидат из снимка списка слушателя.
        java.util.List<MaxSender.Candidate> candidates = MaxSender.candidates();
        assertEquals(1, candidates.size());
        assertTrue(MaxSender.selectCandidate(context, candidates.get(0)));
        assertNotNull(MaxChannel.getTarget(context));
    }

    /**
     * Новый Java-объект той же системной операции PendingIntent (как копия,
     * приехавшая из другого процесса): equals к оригиналу — true, == — false.
     */
    private PendingIntent equalWrapper(PendingIntent original, Intent op) {
        try {
            int requestCode = org.robolectric.util.ReflectionHelpers.getField(
                    org.robolectric.shadow.api.Shadow.extract(original), "requestCode");
            Class<?> senderClass = Class.forName("android.content.IIntentSender");
            Object sender = java.lang.reflect.Proxy.newProxyInstance(
                    senderClass.getClassLoader(), new Class[]{senderClass},
                    (proxy, method, args) -> null);
            PendingIntent copy = org.robolectric.util.ReflectionHelpers.callConstructor(
                    PendingIntent.class,
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                            senderClass, sender));
            Object shadow = org.robolectric.shadow.api.Shadow.extract(copy);
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "savedIntents", new Intent[]{op});
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "savedContext", context);
            org.robolectric.util.ReflectionHelpers.setField(
                    shadow, "requestCode", requestCode);
            assertTrue(original.equals(copy));
            assertTrue(copy.equals(original));
            return copy;
        } catch (Exception e) {
            throw new AssertionError("не удалось создать вторую обёртку операции", e);
        }
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
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        assertSame(piGroup, MaxState.targetButton.pendingIntent);
        // R1: одноимённый чат с другим идентификатором не подменяет выбранного —
        // соответствие не подтверждено → отправка блокируется до повторного
        // уведомления выбранного чата или нового выбора.
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertSame(piGroup, MaxState.targetButton.pendingIntent);
        assertEquals(MaxChannel.BLOCKED_AMBIGUOUS, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.1", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("не подтверждён"));
    }

    @Test
    public void sameNameSameChannelDifferentNotifIdBlocked() {
        // R1: два одноимённых личных чата на ОДНОМ канале — имя+канал не доказывают адресата.
        PendingIntent piA = fakePi();
        PendingIntent piB = fakePi();
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 1, piA));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        // Другая «Семья» на том же канале, другой notificationId — не принимаем.
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 2, piB));
        assertSame(piA, MaxState.targetButton.pendingIntent);
        assertEquals(MaxChannel.BLOCKED_AMBIGUOUS, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.2", clock.get());
        assertEquals(0, sentCommands.size());
        // Подтверждающее уведомление выбранного чата снимает блок и промывает pending.
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 1, fakePi()));
        assertNull(MaxChannel.getBlockedBy(context));
        assertEquals(1, sentCommands.size());
    }

    @Test
    public void sameNameGroupsSameChannelBlocked() {
        // R1: две одноимённые группы на одном канале — та же неоднозначность.
        PendingIntent piA = fakePi();
        listener.onNotificationPosted(maxSbn("Дети", "ru.oneme.app.chats", 10, piA));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        listener.onNotificationPosted(maxSbn("Дети", "ru.oneme.app.chats", 20, fakePi()));
        assertEquals(MaxChannel.BLOCKED_AMBIGUOUS, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.3", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void openSettingsDialogKeepsSnapshotOnNewCandidate() {
        // R2 на настоящем экране настроек: диалог открыт со строкой «Папа»;
        // во время диалога приходит уведомление «Мама» — нажатая показанная
        // строка выбирает именно «Папу», а не сдвинувшегося кандидата.
        listener.onNotificationPosted(
                maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));

        com.example.xdrip2vk.SettingsActivity activity =
                Robolectric.buildActivity(com.example.xdrip2vk.SettingsActivity.class)
                        .create().start().resume().get();
        com.example.xdrip2vk.SettingsActivity.SettingsFragment fragment =
                (com.example.xdrip2vk.SettingsActivity.SettingsFragment) activity
                        .getSupportFragmentManager()
                        .findFragmentById(R.id.settings_container);
        assertNotNull(fragment);

        androidx.preference.Preference select =
                fragment.findPreference("max_select_chat");
        assertNotNull(select);
        select.performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog)
                        org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        android.widget.ListView rows = dialog.getListView();
        assertEquals(1, rows.getAdapter().getCount());
        String shown = rows.getAdapter().getItem(0).toString();
        assertTrue(shown, shown.contains("Папа"));

        // Пока диалог открыт — свежее уведомление другого чата выдвинуло
        // «Папу» с нулевой позиции живого списка кандидатов.
        listener.onNotificationPosted(
                maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        assertEquals(2, MaxSender.candidates().size());
        assertEquals("Мама", MaxSender.candidates().get(0).chatName());

        // Пользователь жмёт показанную строку «Папа» — строка привязана к
        // кандидату из снимка, а не к текущей позиции.
        rows.performItemClick(rows.getAdapter().getView(0, null, rows), 0, 0);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        // Подтверждение показывает фактически выбранного адресата.
        androidx.appcompat.app.AlertDialog confirm =
                (androidx.appcompat.app.AlertDialog)
                        org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(confirm);
        android.widget.TextView message =
                confirm.findViewById(android.R.id.message);
        assertNotNull(message);
        String text = message.getText().toString();
        assertTrue(text, text.contains("Папа"));

        confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        MaxChannel.Target t = MaxChannel.getTarget(context);
        assertNotNull(t);
        assertEquals("Папа", t.chatName);
        assertEquals(378, t.notificationId);
        activity.finish();
    }

    @Test
    public void zeroNotifIdDoesNotWeakenMatch() {
        // MAX-1A.1: выбран «Семья»/dialogs/id=0 (ноль — допустимый id). Приходит
        // другая «Семья», тот же канал, id=2 — соответствие НЕ подтверждено:
        // имя+общий канал не ослабляют проверку ни при каком значении id.
        PendingIntent piA = fakePi();
        PendingIntent piB = fakePi();
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 0, piA));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        assertEquals(0, MaxChannel.getTarget(context).notificationId);

        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 2, piB));
        assertSame("кнопка выбранного чата не подменена", piA,
                MaxState.targetButton.pendingIntent);
        assertEquals(MaxChannel.BLOCKED_AMBIGUOUS, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.0", clock.get());
        assertEquals("показание не отправлено через вторую кнопку",
                0, sentCommands.size());

        // Подтверждающее уведомление выбранного чата (тот же id=0) снимает блок.
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 0, fakePi()));
        assertNull(MaxChannel.getBlockedBy(context));
        assertEquals(1, sentCommands.size());
    }

    @Test
    public void missingNotifIdTargetRequiresReselect() {
        // MAX-1A.1: цель, сохранённая без идентификатора уведомления
        // (NOTIF_ID_UNKNOWN) — это не «id 0»: соответствие не подтверждается
        // ни одним уведомлением, отправка запрещена до повторного выбора.
        maxPrefs.edit()
                .putString("target_name", "Семья")
                .putString("target_channel", "ru.oneme.app.dialogs")
                .commit(); // target_notif_id не записан
        MaxChannel.Target t = MaxChannel.getTarget(context);
        assertNotNull(t);
        assertEquals(MaxChannel.NOTIF_ID_UNKNOWN, t.notificationId);

        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 0, fakePi()));
        assertEquals(MaxChannel.BLOCKED_AMBIGUOUS, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.0", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("не подтверждён"));
    }

    @Test
    public void restoredZeroIdButtonStillWorks() {
        // Идентичность согласована на всех путях: выбранная цель с реальным
        // id=0 восстанавливает кнопку из backup — отправка работает.
        listener.onNotificationPosted(maxSbn("Семья", "ru.oneme.app.dialogs", 0, fakePi()));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        MaxState.targetButton = null; // «перезапуск» — живая кнопка утрачена
        MaxSender.onReading(context, "6.0", clock.get());
        assertEquals(1, sentCommands.size()); // команда ушла по восстановленной кнопке
    }

    @Test
    public void conflictDuringSendBlocksCommand() {
        // Гонка listener↔send: отправка «остановлена» между первой проверкой
        // блокировки и внешней командой; настоящий listener в этот разрыв
        // обрабатывает уведомление чужого чата с равной операцией и ставит
        // BLOCKED_CONFLICT. Команда запрещена, pending сахара сохранён.
        Intent op = new Intent("com.example.xdrip2vk.test.RACE_OP");
        PendingIntent piA = PendingIntent.getBroadcast(context, 88, op, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piB = equalWrapper(piA, op); // другой объект, та же операция

        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, piA));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));

        MaxSender.beforeCommandHookForTest = () ->
                listener.onNotificationPosted(
                        maxSbn("Мама", "ru.oneme.app.dialogs", 555, piB));
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals("конфликт до начала команды обязан её запретить",
                0, sentCommands.size());
        assertNotNull("ожидающее показание сохранено", MaxChannel.getPending(context));
        assertEquals(MaxChannel.BLOCKED_CONFLICT, MaxChannel.getBlockedReason(context));

        // Новое подтверждающее уведомление выбранного чата — один flush, без дубля.
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, fakePi()));
        assertEquals(1, sentCommands.size());
        assertEquals("6.4", fillInText(sentFillIns.get(0)));
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void snapshotSelectsClickedCandidateNotCurrentRow() {
        // R2: строка диалога привязана к снимку — новое уведомление во время
        // открытого диалога не подменяет выбранного адресата.
        PendingIntent piPapa = fakePi();
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, piPapa));
        java.util.List<MaxSender.Candidate> snapshot = MaxSender.candidates();
        // Пока диалог открыт — пришло уведомление другого чата (список вырос).
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, fakePi()));
        assertEquals(2, MaxSender.candidates().size());
        // Пользователь нажимает первую строку СНИМКА — выбирается «Папа», а не «Мама».
        assertTrue(MaxSender.selectCandidate(context, snapshot.get(0)));
        MaxChannel.Target t = MaxChannel.getTarget(context);
        assertEquals("Папа", t.chatName);
        assertEquals(378, t.notificationId);
        assertSame(piPapa, MaxState.targetButton.pendingIntent);
    }

    @Test
    public void equalPendingIntentObjectsConflict() {
        // R3: два РАЗНЫХ объекта PendingIntent для одной операции — equals=true.
        // Совпадение операции у чужого чата — конфликт, отправка блокируется.
        Intent op = new Intent("com.example.xdrip2vk.test.SHARED_OP");
        PendingIntent piA = PendingIntent.getBroadcast(context, 77, op, PendingIntent.FLAG_IMMUTABLE);
        // Второй объект ТОЙ ЖЕ операции: на устройстве PendingIntent равны, когда
        // совпадает системный токен операции — после межпроцессной передачи это
        // новая обёртка того же PI. Моделируем её полями той же операции.
        PendingIntent piB = equalWrapper(piA, op);
        assertNotSame("для проверки нужны разные объекты одной операции", piA, piB);
        assertTrue("ожидались равные операции", piA.equals(piB));

        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, piA));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        // Уведомление другого чата с равной (но не той же) операцией — конфликт.
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, piB));
        assertEquals(MaxChannel.BLOCKED_CONFLICT, MaxChannel.getBlockedReason(context));
        MaxSender.onReading(context, "6.4", clock.get());
        assertEquals(0, sentCommands.size());
    }

    @Test
    public void conflictDetectedAfterButtonRestore() {
        // R3/backup: конфликт ловится и для восстановленной из backup-уведомления
        // кнопки (память очищена) — проверка идёт по копии, а не только по памяти.
        PendingIntent shared = fakePi();
        listener.onNotificationPosted(maxSbn("Папа", "ru.oneme.app.dialogs", 378, shared));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        MaxState.targetButton = null; // «перезапуск процесса» — кнопка только в backup
        // Другой чат с той же операцией ответа → конфликт по восстановленной кнопке.
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, shared));
        assertEquals(MaxChannel.BLOCKED_CONFLICT, MaxChannel.getBlockedReason(context));
    }

    @Test
    public void revokedAccessBlocksMemoryAndBackupButton() {
        // R4: отзыв доступа запрещает отправку по живой и сохранённой кнопке.
        captureTarget();
        selectPapa(null);
        revokeAccess();
        MaxSender.onReading(context, "6.5", clock.get());
        assertEquals(0, sentCommands.size()); // кнопка в памяти — но доступ отозван

        // Кнопка только в backup: тот же запрет.
        MaxState.targetButton = null;
        MaxSender.onReading(context, "6.6", clock.get());
        assertEquals(0, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("Нет доступа"));

        // Восстановление доступа: пригодность кнопки проверяется заново —
        // ожидающее показание уходит.
        grantAccess();
        MaxSender.onButtonCaptured(context);
        assertEquals(1, sentCommands.size());
    }

    @Test
    public void temporaryDisconnectDoesNotBlock() {
        // R4: временное отключение слушателя (доступ есть) — не запрет.
        captureTarget();
        selectPapa(null);
        MaxState.listenerConnected = false;
        MaxSender.onReading(context, "6.7", clock.get());
        assertEquals(1, sentCommands.size());
        assertTrue(MaxSender.statusText(context).contains("отключён"));
    }

    @Test
    public void successfulTestKeepsPendingReading() {
        // R5: успешный тест не удаляет ожидающий сахар и не двигает watermark.
        captureTarget();
        selectPapa(null);
        // Показание ожидает: кнопки нет ни в памяти, ни в backup.
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.8", clock.get());
        assertNotNull(MaxChannel.getPending(context));
        long ts = MaxChannel.getPending(context).readingTs;

        // Кнопка стала доступна, flush ещё не вызван — пользователь жмёт «Тест».
        MaxSender.Candidate c = MaxSender.candidates().get(0);
        MaxState.targetButton = c.button;
        String res = MaxSender.sendTest(context);
        assertTrue(res, res.contains("передано в MAX"));
        // Ожидающий сахар на месте; watermark показания тестом не сдвинут.
        assertNotNull(MaxChannel.getPending(context));
        assertEquals(ts, MaxChannel.getPending(context).readingTs);
        assertNotEquals(ts, MaxChannel.getLastHandoffReadingTs(context));

        // Последующий flush отправляет ожидающее ровно один раз.
        MaxSender.onButtonCaptured(context);
        assertEquals(2, sentFillIns.size()); // тест + показание
        assertEquals("6.8", fillInText(sentFillIns.get(1)));
        assertNull(MaxChannel.getPending(context));
    }

    @Test
    public void failedTestKeepsPendingReading() {
        // R5: неуспешный тест (MAX отменил кнопку) не меняет ожидающий сахар.
        captureTarget();
        selectPapa(null);
        MaxState.targetButton = null;
        MaxReplyBackup.clear(context);
        MaxSender.onReading(context, "6.9", clock.get());
        assertNotNull(MaxChannel.getPending(context));

        MaxSender.Candidate c = MaxSender.candidates().get(0);
        MaxState.targetButton = c.button;
        MaxSender.sendCommandForTest = (ctx, pi, fillIn) -> {
            throw new PendingIntent.CanceledException();
        };
        MaxSender.sendTest(context);
        assertNotNull(MaxChannel.getPending(context)); // тест pending не потреблял
    }

    @Test
    public void sendRequiresButtonOfCurrentTarget() {
        // Перед командой проверяется актуальный адресат: кнопка другого чата
        // (устаревшая после смены выбора) не используется.
        captureTarget();
        selectPapa(null);
        // Прямая смена адресата в состоянии (как повторный выбор «Мамы»).
        PendingIntent piMama = fakePi();
        listener.onNotificationPosted(maxSbn("Мама", "ru.oneme.app.dialogs", 555, piMama));
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
        // В памяти вдруг осталась чужая кнопка — отправка запрещена.
        MaxState.targetButton = new MaxReplyButton("Папа", "ru.oneme.app.dialogs",
                378, fakePi(), new RemoteInput[0], clock.get());
        MaxSender.onReading(context, "7.0", clock.get());
        assertEquals(0, sentCommands.size());
    }

    private void revokeAccess() {
        android.provider.Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners", "");
    }

    private void grantAccess() {
        android.provider.Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners",
                new android.content.ComponentName(context,
                        MaxNotificationListener.class).flattenToString());
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
        assertTrue(MaxSender.selectCandidate(context, MaxSender.candidates().get(0)));
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
