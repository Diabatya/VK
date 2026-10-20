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
    /**
     * Служебное состояние — ключ внутри secure_tokens (тот же файл и
     * тот же commit, что реквизиты): "v1:active"/"v1:deleted".
     * Отсутствие ключа — существующая установка; неизвестное значение —
     * INVALID. Маркер в default prefs не используется: два файла не
     * являются одной транзакцией.
     */
    static final String KEY_STATE = "cred_service_state";
    /**
     * Метаданные полей (флаги явного выбора, 2Г.4Б.1.1) — тот же файл и
     * тот же commit, что реквизиты: передаются внутри serviceState
     * после '|', состояние и мета записываются в разные ключи.
     */
    static final String KEY_META = "cred_field_meta";
    static final String STATE_ACTIVE = "v1:active";
    static final String STATE_DELETED = "v1:deleted";

    /** Граница открытия encrypted-хранилища (AndroidKeyStore). */
    public interface EncryptedOpener {
        SharedPreferences open(Context context) throws Exception;
    }

    /** Фиксированный набор файлов реквизитов — общий id процессного хранилища. */
    private static final Object STORAGE_ID = "xdrip2vk-credentials";

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

    @Override
    public Object storageId() {
        return STORAGE_ID;
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
    public ServiceState serviceState(Role role) {
        if (role != Role.ENCRYPTED) {
            return ServiceState.ABSENT; // состояние живёт только в enc
        }
        try {
            SharedPreferences p = prefs(Role.ENCRYPTED);
            if (!p.contains(KEY_STATE)) {
                return ServiceState.ABSENT;
            }
            String v = p.getString(KEY_STATE, "");
            if (STATE_ACTIVE.equals(v)) {
                return ServiceState.ACTIVE;
            }
            if (STATE_DELETED.equals(v)) {
                return ServiceState.DELETED;
            }
            return ServiceState.INVALID; // неизвестное значение/версия
        } catch (Exception e) {
            Logger.w("CredBackend", "Служебное состояние не читается: "
                    + e.getClass().getSimpleName());
            return ServiceState.INVALID; // блокирующе, без маскировки
        }
    }

    @Override
    public boolean putCredentials(String token, long chatId,
                                  long alertChatId, String serviceState) {
        try {
            SharedPreferences.Editor e = prefs(Role.ENCRYPTED).edit()
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_CHAT, chatId)
                    .putLong(KEY_ALERT, alertChatId)
                    .putString(KEY_STATE,
                            CredentialBackend.serviceStatePart(serviceState));
            String meta = CredentialBackend.fieldMetaPart(serviceState);
            if (meta.isEmpty()) {
                e.remove(KEY_META);
            } else {
                e.putString(KEY_META, meta);
            }
            return e.commit();
        } catch (Exception e) {
            Logger.w("CredBackend", "Запись реквизитов не выполнена: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String credentialMeta(Role role) {
        if (role != Role.ENCRYPTED) {
            return "";
        }
        try {
            SharedPreferences p = prefs(Role.ENCRYPTED);
            if (!p.contains(KEY_META)) {
                return "";
            }
            String v = p.getString(KEY_META, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return ""; // неподтверждённая/нечитаемая мета — нет доказательств
        }
    }

    @Override
    public boolean deleteCredentials() {
        // Подтверждённое удаление одним commit того же файла: реквизиты +
        // служебное состояние. Keyset и сам файл не трогаем.
        try {
            return prefs(Role.ENCRYPTED).edit()
                    .remove(KEY_TOKEN)
                    .remove(KEY_CHAT)
                    .remove(KEY_ALERT)
                    .remove(KEY_META)
                    .putString(KEY_STATE, STATE_DELETED)
                    .commit();
        } catch (Exception e) {
            Logger.w("CredBackend", "Удаление реквизитов не выполнено: "
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
                    .remove(KEY_META)
                    .commit();
        } catch (Exception e) {
            Logger.w("CredBackend", "Очистка реквизитов не выполнена: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }
}
