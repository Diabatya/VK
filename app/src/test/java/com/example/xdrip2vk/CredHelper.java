package com.example.xdrip2vk;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.xdrip2vk.store.AndroidCredentialBackend;
import com.example.xdrip2vk.store.CredentialStore;


/**
 * Подмена границы Keystore для Robolectric-тестов: «encrypted»-источник —
 * обычный SharedPreferences-файл "secure_tokens" через настоящий
 * AndroidCredentialBackend (вся логика снимков/маркеров/коммитов —
 * продакшн). Шифрование/Keystore этим не проверяется — только поведение
 * слоя. Все значения синтетические.
 */
final class CredHelper {

    static SharedPreferences enc(Context c) {
        return c.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE);
    }

    /** Новый процессный фасад над подменённым opener (вызывать в setUp). */
    static void install(Context c) {
        AndroidCredentialBackend b = new AndroidCredentialBackend(c, ctx -> enc(c));
        CredentialStore.resetCoordinatorForTest(b);
        Credentials.resetForTest();
        Credentials.backendForTest = b;
        // Robolectric крутит service worker на главном потоке теста.
        Credentials.permitMainBlockingForTest = true;
        enc(c).edit().clear().commit();
        c.getSharedPreferences("secure_tokens_plain", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    /**
     * «Новый процесс» над теми же файлами: фасад и координатор сброшены,
     * содержимое источников сохранено (незавершённые операции теряются).
     */
    static void recreate(Context c) {
        AndroidCredentialBackend b = new AndroidCredentialBackend(c, ctx -> enc(c));
        CredentialStore.resetCoordinatorForTest(b);
        Credentials.resetForTest();
        Credentials.backendForTest = b;
        Credentials.permitMainBlockingForTest = true;
    }

    // Полевые записи напрямую в «диск» источника — состояние установки.
    static void setToken(Context c, String token) {
        enc(c).edit().putString("vk_token", token).commit();
    }

    static void setChatId(Context c, long v) {
        enc(c).edit().putLong("vk_chat_id", v).commit();
    }

    static void setAlertChatId(Context c, long v) {
        enc(c).edit().putLong("alert_chat_id", v).commit();
    }

    static String getToken(Context c) {
        return enc(c).getString("vk_token", "");
    }

    static long getChatId(Context c) {
        return enc(c).getLong("vk_chat_id", 0);
    }

    static long getAlertChatId(Context c) {
        return enc(c).getLong("alert_chat_id", 0);
    }

    private CredHelper() {}
}
