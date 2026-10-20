package com.example.xdrip2vk.max;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import com.example.xdrip2vk.Logger;
import com.example.xdrip2vk.VkSenderService;

import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Отправка сформированного текста в выбранный чат MAX кнопкой «Ответить» —
 * так же, как если бы пользователь сам ответил на сообщение в шторке.
 * Сетью занимается сам MAX; успешный PendingIntent.send означает только
 * передачу команды приложению MAX, а не доставку.
 *
 * Повторов после успешной передачи нет. Если кнопки нет (перезапуск, MAX её
 * отменил, конфликт, Stop), последнее показание лежит в pending и уходит при
 * следующей кнопке выбранного чата — если к тому моменту не устарело
 * (PENDING_MAX_AGE_MS от времени показания).
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
        MaxChannel.setPending(app, text, readingTs);
        trySend(app, new MaxChannel.Pending(text, readingTs));
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
        trySend(app, pending);
    }

    /**
     * Тестовое сообщение: не откладывается и не считается показанием.
     * Синхронный — вызывать с фонового потока. Возвращает понятный итог.
     */
    public static synchronized String sendTest(Context context) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return "Канал MAX выключен";
        if (MaxChannel.getTarget(app) == null) return "Чат MAX не выбран";
        String text = "Тест: связь xDrip2VK ↔ MAX работает.\nПоказания отправляются в выбранный чат.";
        boolean sent = trySend(app, new MaxChannel.Pending(text, now()));
        return sent ? "Тестовое сообщение передано в MAX — доставка не подтверждена"
                : statusText(app);
    }

    private static boolean trySend(Context app, MaxChannel.Pending item) {
        MaxChannel.Target wanted = MaxChannel.getTarget(app);
        if (wanted == null) {
            MaxChannel.clearPending(app);
            return false;
        }
        if (!MaxChannel.isMaxEnabled(app)) return false;
        // Общий Stop: ручная, авто и отложенная отправка запрещены; ожидающее сохраняем.
        if (!VkSenderService.isTransmissionAllowed(app)) {
            Logger.i(TAG, "не отправлено: передача остановлена");
            return false;
        }
        String blockedBy = MaxChannel.getBlockedBy(app);
        if (blockedBy != null) {
            Logger.w(TAG, "не отправлено: конфликт кнопки ответа — нужно новое сообщение из выбранного чата");
            return false;
        }
        MaxReplyButton button = currentButton(app);
        if (button == null) {
            Logger.w(TAG, "не отправлено: нет кнопки ответа — нужно новое сообщение из выбранного чата");
            return false;
        }
        if (!MaxChannel.MAX_PACKAGE.equals(creatorPackage(button.pendingIntent))) {
            Logger.w(TAG, "не отправлено: кнопка ответа не принадлежит MAX");
            return false;
        }
        if (now() - item.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
            MaxChannel.clearPending(app);
            Logger.i(TAG, "показание устарело — не отправляю");
            return false;
        }

        // Финальный запрет непосредственно перед передачей команды.
        if (!VkSenderService.isTransmissionAllowed(app)) {
            Logger.i(TAG, "не отправлено: передача остановлена перед командой");
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

        try {
            CommandSender sender = sendCommandForTest;
            if (sender != null) {
                sender.send(app, button.pendingIntent, fillIn);
            } else {
                button.pendingIntent.send(app, 0, fillIn);
            }
            MaxChannel.clearPending(app);
            MaxChannel.recordHandoff(app, now(), item.readingTs);
            Logger.i(TAG, "передано в MAX — доставка не подтверждена");
            return true;
        } catch (PendingIntent.CanceledException e) {
            // MAX отменил кнопку — до нового уведомления из чата отправлять нечем.
            MaxState.targetButton = null;
            MaxReplyBackup.clear(app);
            Logger.w(TAG, "не отправлено: MAX отменил кнопку ответа");
            return false;
        } catch (Throwable t) {
            Logger.w(TAG, "не отправлено: ошибка передачи команды в MAX");
            return false;
        }
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

    /**
     * Подписи кандидатов для диалога выбора: «имя — тип · время уведомления».
     * Имена могут совпадать — тип и момент помогают различить чаты.
     */
    public static java.util.List<String> candidateLabels() {
        java.util.List<MaxReplyButton> list = MaxState.candidates();
        java.util.List<String> labels = new java.util.ArrayList<>(list.size());
        java.text.DateFormat tf = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
        for (MaxReplyButton b : list) {
            labels.add(b.chatName + " — " + b.kind() + " · " + tf.format(new java.util.Date(b.capturedAtMs)));
        }
        return labels;
    }

    /**
     * Выбор чата пользователем: записывает адресата и переносит живую кнопку
     * этого кандидата в цель. Ожидающее старое показание новому чату не
     * передаётся — setTarget его очищает.
     */
    public static boolean selectCandidate(Context context, int index) {
        java.util.List<MaxReplyButton> list = MaxState.candidates();
        if (index < 0 || index >= list.size()) return false;
        MaxReplyButton b = list.get(index);
        synchronized (MaxSender.class) {
            MaxChannel.setTarget(context, b);
            MaxState.targetButton = b;
        }
        MaxReplyBackup.save(context, b);
        return true;
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
            return "Отправка заблокирована — нужно новое сообщение из выбранного чата MAX";
        }
        if (currentButton(app) == null) {
            return "Нужно новое сообщение из выбранного чата MAX";
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
