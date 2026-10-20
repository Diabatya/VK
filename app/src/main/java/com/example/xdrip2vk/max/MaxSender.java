package com.example.xdrip2vk.max;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import com.example.xdrip2vk.Logger;
import com.example.xdrip2vk.VkSenderService;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Отправка сформированного текста в выбранный чат MAX кнопкой «Ответить» —
 * так же, как если бы пользователь сам ответил на сообщение в шторке.
 * Сетью занимается сам MAX; успешный PendingIntent.send означает только
 * передачу команды приложению MAX, а не доставку.
 *
 * Повторов после успешной передачи нет. Если кнопки нет (перезапуск, MAX её
 * отменил, конфликт, неоднозначный адресат, отзыв доступа, Stop), последнее
 * показание лежит в pending и уходит при следующей кнопке выбранного чата —
 * если к тому моменту не устарело (PENDING_MAX_AGE_MS от времени показания).
 */
public final class MaxSender {

    private static final String TAG = "MaxSender";

    /** Граница «команда в MAX» — в тестах подменяется, на устройстве зовёт PendingIntent.send. */
    public interface CommandSender {
        void send(Context context, PendingIntent pi, Intent fillIn) throws PendingIntent.CanceledException;
    }

    public static volatile CommandSender sendCommandForTest;
    public static volatile Function<PendingIntent, String> creatorPackageForTest;
    public static volatile LongSupplier clockForTest;
    /**
     * Тестовая точка «между проверкой и командой»: вызывается внутри общей
     * синхронизации непосредственно перед ревалидацией и PendingIntent.send.
     * Позволяет тесту обработать конфликтное уведомление настоящим listener'ом
     * в разрыве между первоначальной проверкой и внешней командой.
     */
    public static volatile Runnable beforeCommandHookForTest;

    private MaxSender() { }

    private static long now() {
        LongSupplier c = clockForTest;
        return c != null ? c.getAsLong() : System.currentTimeMillis();
    }

    /** Пакет-создатель PendingIntent: кнопка должна принадлежать MAX, а не нам и не чужому приложению. */
    private static String creatorPackage(PendingIntent pi) {
        Function<PendingIntent, String> f = creatorPackageForTest;
        if (f != null) return f.apply(pi);
        try {
            return pi.getCreatorPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Кандидаты для диалога выбора (неизменяемый снимок) ----------------------

    /**
     * Строка списка кандидатов: привязана к конкретной захваченной кнопке,
     * а не к позиции в живом списке — новые уведомления во время открытого
     * диалога не меняют то, что выберет пользователь.
     */
    public static final class Candidate {
        final MaxReplyButton button;

        Candidate(MaxReplyButton button) {
            this.button = button;
        }

        public String chatName() {
            return button.chatName;
        }

        public String kind() {
            return button.kind();
        }

        public long capturedAtMs() {
            return button.capturedAtMs;
        }

        /** Подпись строки: «имя — тип · время уведомления». */
        public String label() {
            String t = android.text.format.DateFormat.format("HH:mm", button.capturedAtMs).toString();
            return button.chatName + " — " + button.kind() + " · " + t;
        }
    }

    /** Снимок кандидатов: свежие сверху. Строки диалога строятся из него. */
    public static java.util.List<Candidate> candidates() {
        java.util.List<MaxReplyButton> list = MaxState.candidates();
        java.util.List<Candidate> out = new java.util.ArrayList<>(list.size());
        for (MaxReplyButton b : list) {
            out.add(new Candidate(b));
        }
        return out;
    }

    /**
     * Выбор чата пользователем: записывает адресата и переносит живую кнопку
     * именно этого кандидата в цель — не «текущую строку под тем же индексом».
     * Ожидающее старое показание новому чату не передаётся — setTarget очищает.
     */
    public static boolean selectCandidate(Context context, Candidate candidate) {
        if (candidate == null || candidate.button == null
                || candidate.button.pendingIntent == null) {
            return false;
        }
        synchronized (MaxSender.class) {
            MaxChannel.setTarget(context, candidate.button);
            MaxState.targetButton = candidate.button;
        }
        MaxReplyBackup.save(context, candidate.button);
        return true;
    }

    // --- Приём показаний ---------------------------------------------------------

    /**
     * Свежепринятое показание из общего пайплайна (тот же текст, что уходит в VK).
     * Вызов — из рабочего потока VkSenderService после verdict ACCEPTED.
     */
    public static synchronized void onReading(Context context, String text, long readingTs) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return;
        if (MaxChannel.getTarget(app) == null) return;
        // Одно показание — одна команда: уже переданное не повторяем ни автоматом, ни вручную.
        if (readingTs == MaxChannel.getLastHandoffReadingTs(app)) return;
        // Хранится одно ожидающее: более новое заменяет предыдущее, старое новое не вытесняет.
        MaxChannel.Pending existing = MaxChannel.getPending(app);
        if (existing == null || readingTs > existing.readingTs) {
            MaxChannel.setPending(app, text, readingTs);
        }
        MaxChannel.Pending current = MaxChannel.getPending(app);
        if (current != null) {
            trySend(app, current, true);
        }
    }

    /**
     * Слушатель получил (новую или обновлённую) кнопку выбранного чата —
     * промываем ожидающее показание, если оно ещё актуально.
     */
    public static synchronized void onButtonCaptured(Context context) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return;
        MaxChannel.Pending pending = MaxChannel.getPending(app);
        if (pending == null) return;
        if (now() - pending.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
            MaxChannel.clearPending(app);
            Logger.i(TAG, "отложенное показание устарело — не отправляю");
            return;
        }
        trySend(app, pending, true);
    }

    /**
     * Тестовое сообщение: не откладывается и не считается показанием.
     * Ожидающий сахар не трогает: не очищает pending и не двигает watermark
     * последнего переданного показания — следующий flush отправит его один раз.
     * Синхронный — вызывать с фонового потока. Возвращает понятный итог.
     */
    public static synchronized String sendTest(Context context) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return "Канал MAX выключен";
        if (MaxChannel.getTarget(app) == null) return "Чат MAX не выбран";
        String text = "Тест: связь xDrip2VK ↔ MAX работает.\nПоказания отправляются в выбранный чат.";
        boolean sent = trySend(app, new MaxChannel.Pending(text, now()), false);
        return sent ? "Тестовое сообщение передано в MAX — доставка не подтверждена"
                : statusText(app);
    }

    /**
     * Передача команды. Тело — целиком под монитором MaxSender.class: слушатель
     * меняет адресата/кнопку/блокировку под тем же монитором, поэтому проверки
     * и внешняя команда атомарны относительно обновлений состояния. Вызывается
     * из synchronized-входов (onReading, onButtonCaptured, sendTest) — монитор
     * реентерабелен. Без сетевой и дисковой работы: только чтение prefs и
     * binder-вызов PendingIntent.send.
     */
    private static boolean trySend(Context app, MaxChannel.Pending item, boolean isReading) {
        synchronized (MaxSender.class) {
        MaxChannel.Target wanted = MaxChannel.getTarget(app);
        if (wanted == null) {
            if (isReading) MaxChannel.clearPending(app);
            return false;
        }
        if (!MaxChannel.isMaxEnabled(app)) return false;
        // Общий Stop: ручная, авто и отложенная отправка запрещены; ожидающее сохраняем.
        if (!VkSenderService.isTransmissionAllowed(app)) {
            Logger.i(TAG, "не отправлено: передача остановлена");
            return false;
        }
        // Отзыв доступа слушателя запрещает отправку даже по сохранённой кнопке;
        // временное отключение (listenerConnected=false) запретом не является.
        if (!listenerAccessGranted(app)) {
            Logger.w(TAG, "не отправлено: доступ к уведомлениям отозван");
            return false;
        }
        String blockedBy = MaxChannel.getBlockedBy(app);
        if (blockedBy != null) {
            Logger.w(TAG, "не отправлено: адресат не подтверждён — нужна новая кнопка или повторный выбор");
            return false;
        }
        MaxReplyButton button = currentButton(app);
        if (button == null) {
            Logger.w(TAG, "не отправлено: нет кнопки ответа — нужно новое сообщение из выбранного чата");
            return false;
        }
        // Адресат и кнопка — один снимок: сохранённая/живая кнопка должна
        // принадлежать именно текущему выбранному чату.
        if (!MaxChannel.matches(wanted, button)) {
            Logger.w(TAG, "не отправлено: кнопка не от выбранного чата — нужно новое сообщение");
            if (MaxState.targetButton == button) MaxState.targetButton = null;
            return false;
        }
        if (!MaxChannel.MAX_PACKAGE.equals(creatorPackage(button.pendingIntent))) {
            Logger.w(TAG, "не отправлено: кнопка ответа не принадлежит MAX");
            return false;
        }
        if (isReading && now() - item.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
            MaxChannel.clearPending(app);
            Logger.i(TAG, "показание устарело — не отправляю");
            return false;
        }

        Bundle results = new Bundle();
        for (RemoteInput ri : button.remoteInputs) {
            results.putCharSequence(ri.getResultKey(), item.text);
        }
        Intent fillIn = new Intent();
        RemoteInput.addResultsToIntent(button.remoteInputs, fillIn, results);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
        }

        // Разрыв «после проверки, до команды» — тестовая точка гонки. Затем
        // полная ревалидация: конфликт/смена адресата/отзыв доступа или Stop,
        // обработанные к этому моменту под общим монитором, обязаны запретить
        // команду — одной проверки в начале недостаточно.
        Runnable barrier = beforeCommandHookForTest;
        if (barrier != null) barrier.run();
        if (!stillSendable(app, wanted)) {
            Logger.w(TAG, "не отправлено: адресат/разрешения изменились перед командой");
            return false;
        }

        try {
            CommandSender sender = sendCommandForTest;
            if (sender != null) {
                sender.send(app, button.pendingIntent, fillIn);
            } else {
                button.pendingIntent.send(app, 0, fillIn);
            }
            if (isReading) {
                MaxChannel.clearPending(app);
                MaxChannel.recordReadingHandoff(app, now(), item.readingTs);
            } else {
                MaxChannel.recordTestHandoff(app, now());
            }
            Logger.i(TAG, "передано в MAX — доставка не подтверждена");
            return true;
        } catch (PendingIntent.CanceledException e) {
            // MAX отменил кнопку — до нового уведомления из чата отправлять нечем.
            // Снимаем только эту кнопку: более свежая, если успела прийти, остаётся.
            MaxReplyButton current = MaxState.targetButton;
            if (current == null || Objects.equals(current.pendingIntent, button.pendingIntent)) {
                MaxState.targetButton = null;
                MaxReplyBackup.clear(app);
            }
            Logger.w(TAG, "не отправлено: MAX отменил кнопку ответа");
            return false;
        } catch (Throwable t) {
            Logger.w(TAG, "не отправлено: ошибка передачи команды в MAX");
            return false;
        }
        }
    }

    /**
     * Ревалидация непосредственно перед внешней командой: всё, что могло
     * измениться с первой проверки — включённость канала, общий Stop, доступ
     * к уведомлениям, блокировка, выбранный адресат и кнопка этого адресата.
     * Адресат сравнивается по сохранённой идентичности целиком — нельзя
     * проверить один снимок, а отправить по другому.
     */
    private static boolean stillSendable(Context app, MaxChannel.Target wanted) {
        if (!MaxChannel.isMaxEnabled(app)) return false;
        if (!VkSenderService.isTransmissionAllowed(app)) return false;
        if (!listenerAccessGranted(app)) return false;
        if (MaxChannel.getBlockedBy(app) != null) return false;
        MaxChannel.Target now = MaxChannel.getTarget(app);
        if (now == null || now.notificationId != wanted.notificationId
                || !now.chatName.equals(wanted.chatName)
                || !now.channelId.equals(wanted.channelId)) {
            return false;
        }
        MaxReplyButton current = currentButton(app);
        return current != null && MaxChannel.matches(wanted, current);
    }

    /**
     * Кнопка ответа: из памяти, а после перезапуска процесса — из сохранённой
     * копии (MaxReplyBackup). null — кнопки нет, нужно новое сообщение из чата.
     */
    static MaxReplyButton currentButton(Context app) {
        MaxReplyButton b = MaxState.targetButton;
        if (b != null) return b;
        b = MaxReplyBackup.restore(app);
        if (b != null) {
            MaxState.targetButton = b;
            Logger.i(TAG, "кнопка ответа восстановлена после перезапуска");
        }
        return b;
    }

    /** Полный сброс состояния MAX в памяти и backup-копии — для тестов вне пакета max. */
    public static void resetRuntimeForTest(Context context) {
        MaxState.resetForTest();
        MaxReplyBackup.clear(context);
    }

    /**
     * Подписи кандидатов для совместимости старого вызова — заменено candidates().
     */
    public static java.util.List<String> candidateLabels() {
        java.util.List<Candidate> list = candidates();
        java.util.List<String> labels = new java.util.ArrayList<>(list.size());
        for (Candidate c : list) {
            labels.add(c.label());
        }
        return labels;
    }

    /** Однострочное состояние канала для настроек/диагностики. */
    public static String statusText(Context context) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return "Канал MAX выключен";
        if (!MaxChannel.maxAppInstalled(app)) return "Приложение MAX не установлено";
        if (!listenerAccessGranted(app)) {
            return "Нет доступа к уведомлениям — откройте настройки и разрешите xDrip2VK";
        }
        MaxChannel.Target wanted = MaxChannel.getTarget(app);
        if (wanted == null) {
            return MaxState.candidates().isEmpty()
                    ? "Чат не выбран. Нужно сообщение из чата MAX — он появится в списке выбора"
                    : "Чат не выбран — откройте «Чат MAX» и выберите из списка";
        }
        if (!VkSenderService.isTransmissionAllowed(app)) return "Передача остановлена пользователем";
        String blockedBy = MaxChannel.getBlockedBy(app);
        if (blockedBy != null) {
            return MaxChannel.BLOCKED_AMBIGUOUS.equals(MaxChannel.getBlockedReason(app))
                    ? "Адресат не подтверждён: есть другой чат с таким же именем — выберите чат заново"
                    : "Отправка заблокирована — нужно новое сообщение из выбранного чата MAX";
        }
        if (currentButton(app) == null) {
            return "Нужно новое сообщение из выбранного чата MAX";
        }
        if (!MaxState.listenerConnected) {
            // Доступ есть, слушатель временно отключён системой — не запрет:
            // сохранённая кнопка пригодна, новые кнопки появятся после rebind.
            return "Слушатель уведомлений отключён — ожидание переподключения (кнопка сохранена)";
        }
        long handoff = MaxChannel.getLastHandoffMs(app);
        if (handoff > 0) {
            return "Передано в MAX — доставка не подтверждена ("
                    + android.text.format.DateFormat.format("HH:mm", handoff) + ")";
        }
        return "Готов к отправке в «" + wanted.chatName + "»";
    }

    /** Есть ли у приложения доступ слушателя уведомлений (без перечисления чужих пакетов). */
    public static boolean listenerAccessGranted(Context context) {
        android.content.ComponentName cn =
                new android.content.ComponentName(context, MaxNotificationListener.class);
        String enabled = android.provider.Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(cn.flattenToString());
    }
}
