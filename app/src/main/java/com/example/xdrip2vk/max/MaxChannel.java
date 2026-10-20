package com.example.xdrip2vk.max;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;

/**
 * Канал MAX: персистентное состояние и настройки. Живёт в отдельном файле
 * SharedPreferences "max_channel" — не смешивается с реквизитами VK
 * (secure_tokens) и с очередью delivery_state_v2. Файл попадает под общие
 * исключения backup (домен sharedpref исключён целиком).
 */
public final class MaxChannel {

    /** Пакет приложения MAX — единственный источник уведомлений-кандидатов. */
    public static final String MAX_PACKAGE = "ru.oneme.app";

    /** Ключи в default SharedPreferences (общий экран настроек). */
    public static final String PREF_VK_ENABLED = "send_vk_enabled";
    public static final String PREF_MAX_ENABLED = "send_max_enabled";

    /** Отложенное показание старше этого уже не отправляем — придёт следующее. */
    public static final long PENDING_MAX_AGE_MS = 15 * 60_000L;

    private static final String FILE = "max_channel";

    private static final String KEY_TARGET_NAME = "target_name";
    private static final String KEY_TARGET_CHANNEL = "target_channel";
    private static final String KEY_TARGET_NOTIF = "target_notif_id";
    private static final String KEY_BLOCKED_BY = "blocked_by";
    private static final String KEY_BLOCKED_REASON = "blocked_reason";
    /** Конфликт: у другого чата та же системная операция ответа. */
    public static final String BLOCKED_CONFLICT = "conflict";
    /** Неоднозначность: имя совпадает, но идентификатор чата не подтверждён. */
    public static final String BLOCKED_AMBIGUOUS = "ambiguous";
    private static final String KEY_PENDING_TEXT = "pending_text";
    private static final String KEY_PENDING_TS = "pending_reading_ts";
    private static final String KEY_HANDOFF_TS = "last_handoff_ts";
    private static final String KEY_HANDOFF_READING_TS = "last_handoff_reading_ts";
    private static final String KEY_TEST_HANDOFF_TS = "last_test_handoff_ts";

    private MaxChannel() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- Переключатели каналов -------------------------------------------------

    /** VK-канал включён по умолчанию — поведение существующих установок не меняется. */
    public static boolean isVkEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_VK_ENABLED, true);
    }

    /** MAX-канал выключен по умолчанию — на обновлённых установках не активируется сам. */
    public static boolean isMaxEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_MAX_ENABLED, false);
    }

    /** Установлено ли приложение MAX (сам мессенджер). */
    public static boolean maxAppInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MAX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // --- Выбранный адресат -------------------------------------------------------

    /** Идентификатор уведомления не сохранён — адресат не подтверждён. */
    public static final int NOTIF_ID_UNKNOWN = -1;

    /**
     * Идентичность выбранного чата: имя + канал уведомления + id уведомления.
     * notificationId == NOTIF_ID_UNKNOWN означает, что идентификатора нет —
     * это НЕ то же самое, что сохранённый id 0 (0 — допустимый id уведомления).
     */
    public static final class Target {
        public final String chatName;
        public final String channelId;
        public final int notificationId;

        Target(String chatName, String channelId, int notificationId) {
            this.chatName = chatName;
            this.channelId = channelId;
            this.notificationId = notificationId;
        }
    }

    public static Target getTarget(Context context) {
        SharedPreferences p = prefs(context);
        String name = p.getString(KEY_TARGET_NAME, null);
        if (name == null) return null;
        return new Target(name, p.getString(KEY_TARGET_CHANNEL, ""),
                p.getInt(KEY_TARGET_NOTIF, NOTIF_ID_UNKNOWN));
    }

    /**
     * Выбор нового чата: ожидающее показание и блокировка к новому адресату не
     * переходят — накопленное старое сообщение нельзя перенести в другой чат.
     */
    public static void setTarget(Context context, MaxReplyButton button) {
        prefs(context).edit()
                .putString(KEY_TARGET_NAME, button.chatName)
                .putString(KEY_TARGET_CHANNEL, button.channelId)
                .putInt(KEY_TARGET_NOTIF, button.notificationId)
                .remove(KEY_BLOCKED_BY).remove(KEY_BLOCKED_REASON)
                .remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS)
                .apply();
    }

    public static void clearTarget(Context context) {
        prefs(context).edit()
                .remove(KEY_TARGET_NAME).remove(KEY_TARGET_CHANNEL).remove(KEY_TARGET_NOTIF)
                .remove(KEY_BLOCKED_BY).remove(KEY_BLOCKED_REASON)
                .remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS)
                .apply();
    }

    /**
     * Тот ли это чат: имя, канал и id уведомления совпадают точно. Ослабления
     * нет ни для какого значения: имя и общий канал без подтверждённого id
     * не доказывают адресата — два одноимённых чата на одном канале разные.
     * Сохранённый id 0 — допустимый идентификатор и сравнивается как число;
     * отсутствие id (NOTIF_ID_UNKNOWN) ни с чем не совпадает — адресат требует
     * повторного выбора. Постоянство notificationId на всех версиях MAX на
     * устройстве не проверено; при его смене соответствие не подтверждается
     * — пользователь выбирает чат заново (безопасно).
     */
    static boolean matches(Target target, MaxReplyButton button) {
        if (target.notificationId == NOTIF_ID_UNKNOWN) return false;
        return button.chatName.equals(target.chatName)
                && button.channelId.equals(target.channelId)
                && button.notificationId == target.notificationId;
    }

    /**
     * Имя совпадает, но идентичность не подтверждена: возможно другой чат с
     * таким же названием. Такую кнопку нельзя принимать за выбранный чат.
     */
    static boolean isAmbiguous(Target target, MaxReplyButton button) {
        return button.chatName.equals(target.chatName) && !matches(target, button);
    }

    // --- Блокировка «чужая кнопка» ----------------------------------------------

    public static String getBlockedBy(Context context) {
        return prefs(context).getString(KEY_BLOCKED_BY, null);
    }

    public static void setBlockedBy(Context context, String reason, String chatName) {
        prefs(context).edit()
                .putString(KEY_BLOCKED_BY, chatName)
                .putString(KEY_BLOCKED_REASON, reason)
                .apply();
    }

    public static String getBlockedReason(Context context) {
        return prefs(context).getString(KEY_BLOCKED_REASON, null);
    }

    static void clearBlockedBy(Context context) {
        prefs(context).edit().remove(KEY_BLOCKED_BY).remove(KEY_BLOCKED_REASON).apply();
    }

    // --- Ожидающее показание (одно, последнее) -----------------------------------

    public static final class Pending {
        public final String text;
        public final long readingTs;

        Pending(String text, long readingTs) {
            this.text = text;
            this.readingTs = readingTs;
        }
    }

    public static Pending getPending(Context context) {
        SharedPreferences p = prefs(context);
        String text = p.getString(KEY_PENDING_TEXT, null);
        if (text == null) return null;
        return new Pending(text, p.getLong(KEY_PENDING_TS, 0));
    }

    static void setPending(Context context, String text, long readingTs) {
        prefs(context).edit()
                .putString(KEY_PENDING_TEXT, text)
                .putLong(KEY_PENDING_TS, readingTs)
                .apply();
    }

    static void clearPending(Context context) {
        prefs(context).edit().remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS).apply();
    }

    // --- Факт передачи команды (не доставка!) -------------------------------------

    static void recordReadingHandoff(Context context, long nowMs, long readingTs) {
        prefs(context).edit()
                .putLong(KEY_HANDOFF_TS, nowMs)
                .putLong(KEY_HANDOFF_READING_TS, readingTs)
                .apply();
    }

    /** Факт передачи тестовой команды — отдельно от показаний и их watermark. */
    static void recordTestHandoff(Context context, long nowMs) {
        prefs(context).edit().putLong(KEY_TEST_HANDOFF_TS, nowMs).apply();
    }

    static long getLastTestHandoffMs(Context context) {
        return prefs(context).getLong(KEY_TEST_HANDOFF_TS, 0);
    }

    public static long getLastHandoffMs(Context context) {
        return prefs(context).getLong(KEY_HANDOFF_TS, 0);
    }

    /** Время показания, для которого команда уже была передана (анти-дубль). */
    static long getLastHandoffReadingTs(Context context) {
        return prefs(context).getLong(KEY_HANDOFF_READING_TS, -1);
    }
}
