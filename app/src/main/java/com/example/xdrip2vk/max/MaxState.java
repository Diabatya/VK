/*
 * xDrip to VK — канал отправки в MAX. Copyright © 2026 Diabatya.
 * GPL-3.0-only — см. LICENSE в корне репозитория.
 *
 * Реализовано по подходу и с адаптацией элементов проекта bg2max
 * (GPL-3.0, https://github.com/s-beaver/bg2max, reply-флейвор).
 * Перечень адаптаций и изменений — THIRD_PARTY_NOTICES.md.
 */
package com.example.xdrip2vk.max;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Состояние канала MAX в памяти процесса: слушатель уведомлений пишет, отправка и экран читают. */
final class MaxState {

    /** Живая кнопка ответа выбранного чата; null — отправлять пока некуда. */
    static volatile MaxReplyButton targetButton;

    /** Слушатель уведомлений подключён системой. */
    static volatile boolean listenerConnected;

    /** Последняя запись состояния не подтверждена (commit=false/исключение). */
    static volatile boolean storageError;

    /** Есть попытка команды без подтверждённого исхода — повтор запрещён. */
    static volatile boolean outcomeUnknown;

    /**
     * Чаты, из которых видели уведомление с кнопкой ответа с момента запуска
     * процесса — кандидаты для выбора в настройках. Ключ включает канал, имя и
     * id уведомления: два чата с одинаковым названием остаются разными строками.
     */
    private static final Map<String, MaxReplyButton> candidates = new LinkedHashMap<>();

    private MaxState() { }

    private static String keyOf(MaxReplyButton b) {
        return b.channelId + "\u0000" + b.chatName + "\u0000" + b.notificationId;
    }

    static void addCandidate(MaxReplyButton button) {
        synchronized (candidates) {
            candidates.remove(keyOf(button));
            candidates.put(keyOf(button), button);
        }
    }

    /** Самые свежие сверху. */
    static List<MaxReplyButton> candidates() {
        synchronized (candidates) {
            List<MaxReplyButton> list = new ArrayList<>(candidates.values());
            List<MaxReplyButton> reversed = new ArrayList<>(list.size());
            for (int i = list.size() - 1; i >= 0; i--) reversed.add(list.get(i));
            return reversed;
        }
    }

    static void resetForTest() {
        targetButton = null;
        listenerConnected = false;
        storageError = false;
        outcomeUnknown = false;
        synchronized (candidates) {
            candidates.clear();
        }
    }
}
