package com.example.xdrip2vk;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

public final class TokenStorage {

    private static final String FILENAME = "secure_tokens";
    private static final String PREF_VK_TOKEN = "vk_token";
    private static final String PREF_VK_CHAT_ID = "vk_chat_id";
    private static final String PREF_ALERT_CHAT_ID = "alert_chat_id";

    private static SharedPreferences getPrefs(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    masterKeyAlias,
                    FILENAME,
                    context.getApplicationContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Диагностика без e.getMessage(): произвольный текст исключения
            // в лог не передаётся.
            Logger.w("TokenStorage", "Защищённое хранилище недоступно, используем обычное: "
                    + e.getClass().getSimpleName());
            return context.getApplicationContext().getSharedPreferences(FILENAME + "_plain", Context.MODE_PRIVATE);
        }
    }

    private static void migrateOnce(Context context) {
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        if (!defaultPrefs.contains(PREF_VK_TOKEN)
                && !defaultPrefs.contains(PREF_VK_CHAT_ID)
                && !defaultPrefs.contains(PREF_ALERT_CHAT_ID)) {
            return;
        }

        SharedPreferences.Editor secureEditor = getPrefs(context).edit();
        if (defaultPrefs.contains(PREF_VK_TOKEN)) {
            String token = defaultPrefs.getString(PREF_VK_TOKEN, "");
            if (token != null && !token.isEmpty()) {
                secureEditor.putString(PREF_VK_TOKEN, token.trim());
            }
        }
        if (defaultPrefs.contains(PREF_VK_CHAT_ID)) {
            secureEditor.putLong(PREF_VK_CHAT_ID, defaultPrefs.getLong(PREF_VK_CHAT_ID, 0));
        }
        if (defaultPrefs.contains(PREF_ALERT_CHAT_ID)) {
            secureEditor.putLong(PREF_ALERT_CHAT_ID, defaultPrefs.getLong(PREF_ALERT_CHAT_ID, 0));
        }
        secureEditor.apply();

        defaultPrefs.edit()
                .remove(PREF_VK_TOKEN)
                .remove(PREF_VK_CHAT_ID)
                .remove(PREF_ALERT_CHAT_ID)
                .apply();
    }

    public static String getToken(Context context) {
        migrateOnce(context);
        return getPrefs(context).getString(PREF_VK_TOKEN, "");
    }

    public static void setToken(Context context, String token) {
        getPrefs(context).edit()
                .putString(PREF_VK_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    public static long getChatId(Context context) {
        migrateOnce(context);
        return getPrefs(context).getLong(PREF_VK_CHAT_ID, 0);
    }

    public static void setChatId(Context context, long chatId) {
        getPrefs(context).edit()
                .putLong(PREF_VK_CHAT_ID, chatId)
                .apply();
    }

    public static long getAlertChatId(Context context) {
        migrateOnce(context);
        return getPrefs(context).getLong(PREF_ALERT_CHAT_ID, 0);
    }

    public static void setAlertChatId(Context context, long chatId) {
        getPrefs(context).edit()
                .putLong(PREF_ALERT_CHAT_ID, chatId)
                .apply();
    }
}
