/*
 * xDrip to VK — канал отправки в MAX. Copyright © 2026 Diabatya.
 * GPL-3.0-only — см. LICENSE в корне репозитория.
 *
 * Реализовано по подходу и с адаптацией элементов проекта bg2max
 * (GPL-3.0, https://github.com/s-beaver/bg2max, reply-флейвор).
 * Перечень адаптаций и изменений — THIRD_PARTY_NOTICES.md.
 */
package com.example.xdrip2vk.max;

import android.app.PendingIntent;
import android.app.RemoteInput;

/**
 * Кнопка «Ответить» из уведомления MAX одного чата. PendingIntent живёт только
 * в памяти процесса (и в копии — MaxReplyBackup) — на диск он не пишется.
 * Ответ, отправленный этой кнопкой, уходит от имени аккаунта MAX в чат,
 * которому принадлежало уведомление.
 */
final class MaxReplyButton {
    final String chatName;
    /** Канал уведомления MAX: у личных диалогов и групп разные — отличает «Папа» от группы «Папа». */
    final String channelId;
    /** Id уведомления в MAX: у одного чата обычно постоянный — дополнительный признак чата. */
    final int notificationId;
    final PendingIntent pendingIntent;
    final RemoteInput[] remoteInputs;
    final long capturedAtMs;

    MaxReplyButton(String chatName, String channelId, int notificationId,
                   PendingIntent pendingIntent, RemoteInput[] remoteInputs, long capturedAtMs) {
        this.chatName = chatName;
        this.channelId = channelId == null ? "" : channelId;
        this.notificationId = notificationId;
        this.pendingIntent = pendingIntent;
        this.remoteInputs = remoteInputs == null ? new RemoteInput[0] : remoteInputs;
        this.capturedAtMs = capturedAtMs;
    }

    /** Человекочитаемый тип для подтверждения выбора; не используется как признак идентичности. */
    String kind() {
        if (channelId.contains("chat")) return "группа";
        if (channelId.contains("dialog")) return "личный чат";
        return channelId.isEmpty() ? "чат" : "канал " + channelId;
    }
}
