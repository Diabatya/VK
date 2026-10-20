/*
 * xDrip to VK — канал отправки в MAX. Copyright © 2026 Diabatya.
 * GPL-3.0-only — см. LICENSE в корне репозитория.
 *
 * Реализовано по подходу и с адаптацией элементов проекта bg2max
 * (GPL-3.0, https://github.com/s-beaver/bg2max, reply-флейвор).
 * Перечень адаптаций и изменений — THIRD_PARTY_NOTICES.md.
 */
package com.example.xdrip2vk.max;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.example.xdrip2vk.Logger;
import com.example.xdrip2vk.R;

/**
 * Копия кнопки ответа, переживающая перезапуск процесса.
 *
 * PendingIntent нельзя записать на диск, но он живёт в системе, пока уведомление
 * существует. Кнопку кладём в extras своего тихого ongoing-уведомления: после
 * перезапуска достаём оттуда — новое сообщение в чате не нужно. Не помогает при
 * принудительной остановке приложения и если пользователь смахнул уведомление.
 */
final class MaxReplyBackup {

    static final String CHANNEL_ID = "xdrip2vk_max_reply_backup";
    private static final int NOTIFICATION_ID = 7; // 1 занято уведомлением сервиса

    private static final String KEY_ACTION = "xdrip2vk.max.action";
    private static final String KEY_CHAT_NAME = "xdrip2vk.max.chatName";
    private static final String KEY_CHANNEL_ID = "xdrip2vk.max.channelId";
    private static final String KEY_NOTIFICATION_ID = "xdrip2vk.max.notificationId";
    private static final String KEY_CAPTURED_AT = "xdrip2vk.max.capturedAt";

    private static final String TAG = "MaxReplyBackup";

    private MaxReplyBackup() { }

    static void save(Context context, MaxReplyButton button) {
        Context app = context.getApplicationContext();
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Связь с MAX", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Хранит кнопку ответа MAX, чтобы отправка продолжилась после перезапуска");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Action.Builder ab =
                new Notification.Action.Builder(null, "reply", button.pendingIntent);
        for (RemoteInput ri : button.remoteInputs) {
            ab.addRemoteInput(ri);
        }
        Bundle extras = new Bundle();
        extras.putParcelable(KEY_ACTION, ab.build());
        extras.putString(KEY_CHAT_NAME, button.chatName);
        extras.putString(KEY_CHANNEL_ID, button.channelId);
        extras.putInt(KEY_NOTIFICATION_ID, button.notificationId);
        extras.putLong(KEY_CAPTURED_AT, button.capturedAtMs);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(app, CHANNEL_ID)
                : new Notification.Builder(app);
        Notification notification = nb
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Связь с «" + button.chatName + "» сохранена")
                .setContentText("Не смахивайте: по ней отправка в MAX продолжится после перезапуска")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addExtras(extras)
                .build();
        try {
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Logger.w(TAG, "не удалось сохранить кнопку ответа");
        }
    }

    static void clear(Context context) {
        context.getApplicationContext()
                .getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    /**
     * Кнопка из сохранённого уведомления, если она от выбранного сейчас чата;
     * иначе null. Своё уведомление читается через NotificationManager — доступ
     * слушателя для этого не нужен.
     */
    static MaxReplyButton restore(Context context) {
        Context app = context.getApplicationContext();
        MaxChannel.Target wanted = MaxChannel.getTarget(app);
        if (wanted == null) return null;
        StatusBarNotification[] active;
        try {
            active = app.getSystemService(NotificationManager.class).getActiveNotifications();
        } catch (Throwable t) {
            return null;
        }
        StatusBarNotification sbn = null;
        if (active != null) {
            for (StatusBarNotification s : active) {
                if (s.getId() == NOTIFICATION_ID && app.getPackageName().equals(s.getPackageName())) {
                    sbn = s;
                    break;
                }
            }
        }
        if (sbn == null) return null;
        Bundle extras = sbn.getNotification().extras;
        Notification.Action action;
        if (Build.VERSION.SDK_INT >= 33) {
            action = extras.getParcelable(KEY_ACTION, Notification.Action.class);
        } else {
            action = extras.getParcelable(KEY_ACTION);
        }
        PendingIntent intent = action == null ? null : action.actionIntent;
        RemoteInput[] inputs = action == null ? null : action.getRemoteInputs();
        if (intent == null || inputs == null || inputs.length == 0) {
            clear(app);
            return null;
        }
        MaxReplyButton button = new MaxReplyButton(
                extras.getString(KEY_CHAT_NAME, ""),
                extras.getString(KEY_CHANNEL_ID, ""),
                extras.getInt(KEY_NOTIFICATION_ID),
                intent, inputs,
                extras.getLong(KEY_CAPTURED_AT));
        if (!MaxChannel.matches(wanted, button)) {
            clear(app);
            return null;
        }
        return button;
    }
}
