package com.example.xdrip2vk;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.xdrip2vk.core.CredentialAudit;
import com.example.xdrip2vk.store.AndroidCredentialBackend;
import com.example.xdrip2vk.store.CredentialBackend;
import com.example.xdrip2vk.store.CredentialStore;

import java.util.Map;
import java.util.Set;


/**
 * Подмена границы Keystore для Robolectric-тестов: «encrypted»-источник —
 * обычный SharedPreferences-файл "secure_tokens" через настоящий
 * AndroidCredentialBackend (вся логика снимков/маркеров/коммитов —
 * продакшн). Шифрование/Keystore этим не проверяется — только поведение
 * слоя. Все значения синтетические.
 */
final class CredHelper {

    /**
     * Делегирующий backend с барьерными хуками чтения/записи —
     * для точных воспроизведений гонок без sleep.
     */
    static final class HookedBackend implements CredentialBackend {
        interface Put {
            boolean call(String token, long chatId, long alertChatId, String state);
        }
        final CredentialBackend d;
        java.util.function.Function<CredentialAudit.Role, CredentialAudit.Source>
                snapshotOverride;
        Put putOverride;

        HookedBackend(CredentialBackend d) { this.d = d; }

        @Override public Object storageId() { return d.storageId(); }
        @Override public CredentialAudit.Source snapshot(CredentialAudit.Role role) {
            return snapshotOverride != null
                    ? snapshotOverride.apply(role) : d.snapshot(role);
        }
        @Override public ServiceState serviceState(CredentialAudit.Role role) {
            return d.serviceState(role);
        }
        @Override public boolean putCredentials(String t, long c, long a, String st) {
            return putOverride != null ? putOverride.call(t, c, a, st)
                    : d.putCredentials(t, c, a, st);
        }
        @Override public String credentialMeta(CredentialAudit.Role role) {
            return d.credentialMeta(role);
        }
        @Override public boolean deleteCredentials() { return d.deleteCredentials(); }
        @Override public boolean removeCredentials(CredentialAudit.Role role) {
            return d.removeCredentials(role);
        }
    }

    /**
     * Делегирующий SharedPreferences для модели «commit=false с грязным
     * кешем» на выбранном ключе (2Г.4Б.1.1): commit, трогающий ключ,
     * при установленном флаге применяет правки в память делегата
     * (читаемы всеми), возвращает false и не помечает ключ записанным
     * на диск. Остальные commit проходят в делегат.
     */
    static final class DirtySharedPreferences implements SharedPreferences {
        final SharedPreferences d;
        final String dirtyKey;
        final java.util.concurrent.atomic.AtomicBoolean failing;
        /** Сколько commit коснулось ключа (все — и отклонённые). */
        final java.util.concurrent.atomic.AtomicInteger keyCommits =
                new java.util.concurrent.atomic.AtomicInteger();
        /** Есть ли ключ на «диске» по модели теста. */
        volatile boolean keyOnDisk;
        /** Журнал порядка операций: "key:true|false". */
        final java.util.List<String> opLog =
                java.util.Collections.synchronizedList(
                        new java.util.ArrayList<String>());

        DirtySharedPreferences(SharedPreferences d, String dirtyKey,
                               java.util.concurrent.atomic.AtomicBoolean failing) {
            this.d = d;
            this.dirtyKey = dirtyKey;
            this.failing = failing;
            this.keyOnDisk = d.contains(dirtyKey);
        }

        @Override public Map<String, ?> getAll() { return d.getAll(); }
        @Override public String getString(String k, String v) { return d.getString(k, v); }
        @Override public Set<String> getStringSet(String k, Set<String> v) { return d.getStringSet(k, v); }
        @Override public int getInt(String k, int v) { return d.getInt(k, v); }
        @Override public long getLong(String k, long v) { return d.getLong(k, v); }
        @Override public float getFloat(String k, float v) { return d.getFloat(k, v); }
        @Override public boolean getBoolean(String k, boolean v) { return d.getBoolean(k, v); }
        @Override public boolean contains(String k) { return d.contains(k); }
        @Override public Editor edit() { return new DirtyEditor(d.edit()); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
            d.registerOnSharedPreferenceChangeListener(l);
        }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
            d.unregisterOnSharedPreferenceChangeListener(l);
        }

        final class DirtyEditor implements Editor {
            final Editor inner;
            boolean touchesKey;
            boolean keyPut;

            DirtyEditor(Editor inner) { this.inner = inner; }

            private void track(String k, boolean put) {
                if (dirtyKey.equals(k)) {
                    touchesKey = true;
                    keyPut = put;
                }
            }

            @Override public Editor putString(String k, String v) {
                track(k, true);
                inner.putString(k, v);
                return this;
            }
            @Override public Editor putStringSet(String k, Set<String> v) {
                track(k, true);
                inner.putStringSet(k, v);
                return this;
            }
            @Override public Editor putInt(String k, int v) {
                track(k, true);
                inner.putInt(k, v);
                return this;
            }
            @Override public Editor putLong(String k, long v) {
                track(k, true);
                inner.putLong(k, v);
                return this;
            }
            @Override public Editor putFloat(String k, float v) {
                track(k, true);
                inner.putFloat(k, v);
                return this;
            }
            @Override public Editor putBoolean(String k, boolean v) {
                track(k, true);
                inner.putBoolean(k, v);
                return this;
            }
            @Override public Editor remove(String k) {
                track(k, false);
                inner.remove(k);
                return this;
            }
            @Override public Editor clear() {
                touchesKey = true;
                keyPut = false;
                inner.clear();
                return this;
            }
            @Override public boolean commit() {
                if (touchesKey) {
                    keyCommits.incrementAndGet();
                }
                if (touchesKey && failing.get()) {
                    // Кеш меняется (значение читается всеми), commit
                    // отклонён: ключ на «диск» не переносится.
                    inner.apply();
                    opLog.add("key:false");
                    return false;
                }
                boolean ok = inner.commit();
                if (touchesKey) {
                    opLog.add("key:" + ok);
                    if (ok) {
                        keyOnDisk = keyPut;
                    }
                }
                return ok;
            }
            @Override public void apply() {
                inner.apply();
            }
        }
    }


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
