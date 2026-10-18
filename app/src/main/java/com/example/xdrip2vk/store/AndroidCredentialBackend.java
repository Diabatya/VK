package com.example.xdrip2vk.store;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.xdrip2vk.Logger;
import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Read;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;

/**
 * Android-адаптер {@link CredentialBackend} (задание 2Г.3Б): читает
 * снимки настоящих хранилищ и выполняет commit-операции.
 *
 * - encrypted: существующий secure_tokens, прежние alias и схемы;
 * - plain: secure_tokens_plain;
 * - default: только vk_token/vk_chat_id/alert_chat_id.
 *
 * Не вызывает TokenStorage.migrateOnce. Не переходит на plaintext при
 * исключении, не удаляет повреждённые файлы и не пересоздаёт хранилище.
 * Открытие encrypted подменяемо через {@link EncryptedOpener} — граница
 * AndroidKeyStore для тестов (это не проверка реального шифрования).
 *
 * Служебные записи keyset — это записи внутри файла secure_tokens;
 * снимок читает только три ключа реквизитов, поэтому keyset в
 * CredentialAudit не попадает. Диагностика — фиксированные причины,
 * без значений и произвольного текста исключений.
 */
public final class AndroidCredentialBackend implements CredentialBackend {

    static final String FILE_SECURE = "secure_tokens";
    static final String FILE_PLAIN = "secure_tokens_plain";
    static final String KEY_TOKEN = "vk_token";
    static final String KEY_CHAT = "vk_chat_id";
    static final String KEY_ALERT = "alert_chat_id";

    /** Граница открытия encrypted-хранилища (AndroidKeyStore). */
    public interface EncryptedOpener {
        SharedPreferences open(Context context) throws Exception;
    }

    private final Context appContext;
    private final EncryptedOpener opener;
    private volatile SharedPreferences encrypted;

    public AndroidCredentialBackend(Context context) {
        this(context, c -> EncryptedSharedPreferences.create(
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                FILE_SECURE, c,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme
                        .AES256_GCM));
    }

    public AndroidCredentialBackend(Context context, EncryptedOpener opener) {
        this.appContext = context.getApplicationContext();
        this.opener = opener;
    }

    private SharedPreferences prefs(Role role) throws Exception {
        switch (role) {
            case ENCRYPTED:
                SharedPreferences e = encrypted;
                if (e == null) {
                    e = opener.open(appContext); // Keystore/IO могут падать
                    encrypted = e;
                }
                return e;
            case PLAIN:
                return appContext.getSharedPreferences(FILE_PLAIN,
                        Context.MODE_PRIVATE);
            case DEFAULT:
            default:
                return PreferenceManager.getDefaultSharedPreferences(
                        appContext);
        }
    }

    private static Entry readString(SharedPreferences p, String key) {
        try {
            if (!p.contains(key)) {
                return Entry.absent();
            }
            return Entry.string(p.getString(key, ""));
        } catch (ClassCastException e) {
            return Entry.wrongType(); // неверный тип — не «отсутствие»
        }
    }

    private static Entry readLong(SharedPreferences p, String key) {
        try {
            if (!p.contains(key)) {
                return Entry.absent();
            }
            // Числовую строку/float в long не преобразуем: тип фиксирован.
            return Entry.number(p.getLong(key, 0));
        } catch (ClassCastException e) {
            return Entry.wrongType();
        }
    }

    @Override
    public Source snapshot(Role role) {
        SharedPreferences p;
        try {
            p = prefs(role);
        } catch (Exception e) {
            // Открытие не удалось — безопасный блокирующий статус,
            // без e.getMessage() и без перехода на plaintext.
            Logger.w("CredBackend", "Источник реквизитов недоступен: "
                    + e.getClass().getSimpleName());
            return Source.unavailable(role);
        }
        try {
            return Source.of(role, Read.READABLE,
                    readString(p, KEY_TOKEN), readLong(p, KEY_CHAT),
                    readLong(p, KEY_ALERT));
        } catch (RuntimeException e) {
            // Ошибка внутри чтения (в т.ч. расшифровки) — повреждение.
            Logger.w("CredBackend", "Чтение реквизитов повреждено: "
                    + e.getClass().getSimpleName());
            return Source.corrupt(role);
        }
    }

    @Override
    public boolean putCredentials(String token, long chatId,
                                  long alertChatId) {
        try {
            return prefs(Role.ENCRYPTED).edit()
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_CHAT, chatId)
                    .putLong(KEY_ALERT, alertChatId)
                    .commit();
        } catch (Exception e) {
            Logger.w("CredBackend", "Запись реквизитов не выполнена: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean removeCredentials(Role role) {
        // Только три ключа реквизитов — clear() затронул бы очередь,
        // настройки или записи keyset в том же файле.
        try {
            return prefs(role).edit()
                    .remove(KEY_TOKEN)
                    .remove(KEY_CHAT)
                    .remove(KEY_ALERT)
                    .commit();
        } catch (Exception e) {
            Logger.w("CredBackend", "Очистка реквизитов не выполнена: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }
}
