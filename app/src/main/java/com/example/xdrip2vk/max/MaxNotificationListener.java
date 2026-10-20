package com.example.xdrip2vk.max;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.os.Bundle;
import java.util.Objects;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;

import com.example.xdrip2vk.Logger;

/**
 * Слушатель уведомлений MAX: находит кнопку «Ответить» в уведомлениях пакета
 * ru.oneme.app и предоставляет её отправщику. Чужие приложения игнорируются.
 * Тексты сообщений и extras уведомлений не читаются и не хранятся.
 *
 * Механизм повторяет подход проекта bg2max (GPL-3.0): собственная реализация
 * на Java по той же схеме — фильтр по пакету, RemoteInput с allowFreeFormInput,
 * идентификация чата по имени+каналу+id уведомления, блокировка при совпадении
 * PendingIntent у другого чата.
 */
public class MaxNotificationListener extends NotificationListenerService {

    private static final String TAG = "MaxNotifListener";

    @Override
    public void onListenerConnected() {
        MaxState.listenerConnected = true;
        // После (пере)подключения восстанавливаем кандидатов из активных уведомлений.
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            active = null;
        }
        if (active == null) return;
        for (StatusBarNotification sbn : active) {
            if (MaxChannel.MAX_PACKAGE.equals(sbn.getPackageName())) {
                process(sbn, "reconnect");
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        MaxState.listenerConnected = false;
        requestRebind(new android.content.ComponentName(this, MaxNotificationListener.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !MaxChannel.MAX_PACKAGE.equals(sbn.getPackageName())) return;
        process(sbn, "post");
    }

    private void process(StatusBarNotification sbn, String event) {
        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // сводка группы — не чат

        Bundle ex = n.extras;
        CharSequence conv = ex == null ? null : ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        String chatName = conv != null ? conv.toString() : (title != null ? title.toString() : "");
        if (chatName.isEmpty()) return;

        MaxReplyButton button = findReplyButton(n, chatName, sbn.getId());
        if (button == null) return; // уведомление без кнопки ответа не даёт адресата

        MaxState.addCandidate(button);

        // Все решения о выбранном адресате, живой кнопке и блокировке — под
        // общим монитором отправщика: конфликт, обработанный до начала
        // внешней команды, обязан её запретить, а не проскочить между
        // проверкой и PendingIntent.send. Монитор реентерабелен:
        // onButtonCaptured → trySend отрабатывают внутри того же захвата.
        synchronized (MaxSender.class) {
            MaxChannel.Target wanted = MaxChannel.getTarget(this);
            if (wanted == null) return; // чат не выбран — только пополняем кандидатов

            if (MaxChannel.matches(wanted, button)) {
                // Подтверждённый чат: кнопка обновляется, блокировка снимается.
                MaxReplyButton saved = MaxSender.currentButton(this);
                boolean renewed = saved == null
                        || !Objects.equals(saved.pendingIntent, button.pendingIntent);
                MaxState.targetButton = button;
                if (MaxChannel.getBlockedBy(this) != null) {
                    MaxChannel.clearBlockedBy(this);
                    Logger.i(TAG, "новое сообщение из выбранного чата — блокировка снята");
                }
                if (renewed) MaxReplyBackup.save(this, button);
                MaxSender.onButtonCaptured(this);
                return;
            }

            // Чужой чат с той же системной операцией ответа (Objects.equals
            // сравнивает операцию PendingIntent, а не Java-объект): ответ мог бы
            // уйти не туда — блокируем до нового уведомления выбранного чата.
            MaxReplyButton saved = MaxSender.currentButton(this);
            if (saved != null && Objects.equals(button.pendingIntent, saved.pendingIntent)) {
                MaxState.targetButton = null;
                MaxReplyBackup.clear(this);
                MaxChannel.setBlockedBy(this, MaxChannel.BLOCKED_CONFLICT, chatName);
                Logger.w(TAG, event + ": у другого чата та же операция ответа — отправка заблокирована");
                return;
            }

            // Имя совпадает, но идентичность не подтверждена (другой
            // notificationId или идентификатор не сохранён): возможно
            // одноимённый чат — принимать его кнопку за выбранный чат нельзя.
            if (MaxChannel.isAmbiguous(wanted, button)) {
                MaxChannel.setBlockedBy(this, MaxChannel.BLOCKED_AMBIGUOUS, chatName);
                Logger.w(TAG, event + ": одноимённый чат с другим идентификатором — нужен повторный выбор");
            }
        }
    }

    /**
     * Кнопка «Ответить»: action со свободным RemoteInput. Проверяются основные
     * действия и действия для часов — у MAX ответ бывает и там.
     */
    private MaxReplyButton findReplyButton(Notification n, String chatName, int notificationId) {
        long now = System.currentTimeMillis();
        String channel = n.getChannelId() == null ? "" : n.getChannelId();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                RemoteInput[] inputs = a.getRemoteInputs();
                if (a.actionIntent != null && hasFreeForm(inputs)) {
                    return new MaxReplyButton(chatName, channel, notificationId,
                            a.actionIntent, inputs, now);
                }
            }
        }
        for (NotificationCompat.Action a : new NotificationCompat.WearableExtender(n).getActions()) {
            androidx.core.app.RemoteInput[] inputs = a.getRemoteInputs();
            PendingIntent intent = a.actionIntent;
            if (intent != null && hasFreeFormCompat(inputs)) {
                // RemoteInput из compat-обёртки пересобираем в платформенный.
                RemoteInput[] platform = new RemoteInput[inputs.length];
                for (int i = 0; i < inputs.length; i++) {
                    androidx.core.app.RemoteInput src = inputs[i];
                    platform[i] = new RemoteInput.Builder(src.getResultKey())
                            .setLabel(src.getLabel())
                            .setAllowFreeFormInput(src.getAllowFreeFormInput())
                            .setChoices(src.getChoices())
                            .addExtras(src.getExtras())
                            .build();
                }
                return new MaxReplyButton(chatName, channel, notificationId, intent, platform, now);
            }
        }
        return null;
    }

    private static boolean hasFreeForm(RemoteInput[] inputs) {
        if (inputs == null) return false;
        for (RemoteInput ri : inputs) {
            if (ri != null && ri.getAllowFreeFormInput()) return true;
        }
        return false;
    }

    private static boolean hasFreeFormCompat(androidx.core.app.RemoteInput[] inputs) {
        if (inputs == null) return false;
        for (androidx.core.app.RemoteInput ri : inputs) {
            if (ri != null && ri.getAllowFreeFormInput()) return true;
        }
        return false;
    }

}
