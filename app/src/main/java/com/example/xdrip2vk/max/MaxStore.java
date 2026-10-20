/*
 * xDrip to VK — канал отправки в MAX. Copyright © 2026 Diabatya.
 * GPL-3.0-only — см. LICENSE в корне репозитория.
 *
 * Реализовано по подходу и с адаптацией элементов проекта bg2max
 * (GPL-3.0, https://github.com/s-beaver/bg2max, reply-флейвор).
 * Перечень адаптаций и изменений — THIRD_PARTY_NOTICES.md.
 */
package com.example.xdrip2vk.max;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Граница хранилища состояния MAX. Запись считается подтверждённой только при
 * commit()==true, вызванном на рабочем исполнителе MaxSender. Новый handle
 * того же хранилища не обходит отказ записи — подтверждённость определяется
 * результатом commit, а не объектом.
 *
 * В тестах подменяется через MaxChannel.storeFactoryForTest: fake-хранилище
 * с отдельными «диском» и «кешем», удержаниями и отказами commit.
 */
interface MaxStore {

    String getString(String key, String def);

    long getLong(String key, long def);

    int getInt(String key, int def);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putLong(String key, long value);

        Editor putInt(String key, int value);

        Editor remove(String key);

        /** Синхронная запись на вызывающем потоке; true — запись подтверждена. */
        boolean commit();
    }

    /** Обёртка над SharedPreferences: commit() делает синхронную запись. */
    final class PrefsStore implements MaxStore {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override public String getString(String key, String def) {
            return prefs.getString(key, def);
        }

        @Override public long getLong(String key, long def) {
            return prefs.getLong(key, def);
        }

        @Override public int getInt(String key, int def) {
            return prefs.getInt(key, def);
        }

        @Override public Editor edit() {
            final SharedPreferences.Editor e = prefs.edit();
            return new Editor() {
                @Override public Editor putString(String k, String v) { e.putString(k, v); return this; }
                @Override public Editor putLong(String k, long v) { e.putLong(k, v); return this; }
                @Override public Editor putInt(String k, int v) { e.putInt(k, v); return this; }
                @Override public Editor remove(String k) { e.remove(k); return this; }
                @Override public boolean commit() { return e.commit(); }
            };
        }
    }
}
