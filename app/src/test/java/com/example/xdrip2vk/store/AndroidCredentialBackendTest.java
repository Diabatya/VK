package com.example.xdrip2vk.store;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Read;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Настоящий AndroidCredentialBackend под Robolectric: реальные
 * SharedPreferences для plain/default, а «encrypted» подменяется на
 * границе открытия хранилища (EncryptedOpener) — это НЕ проверка
 * реального шифрования и Keystore.
 *
 * Проверяется: присутствие ключей и типы в снимке, чтение legacy-файлов,
 * отсутствие keyset-записей в снимке, точечная очистка трёх ключей.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AndroidCredentialBackendTest {

    private Context context;
    private SharedPreferences secure; // стоит вместо EncryptedSharedPreferences
    private SharedPreferences plain;
    private SharedPreferences defaults;
    private AndroidCredentialBackend backend;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        secure = context.getSharedPreferences("secure_tokens",
                Context.MODE_PRIVATE);
        plain = context.getSharedPreferences("secure_tokens_plain",
                Context.MODE_PRIVATE);
        defaults = PreferenceManager.getDefaultSharedPreferences(context);
        secure.edit().clear().commit();
        plain.edit().clear().commit();
        defaults.edit().remove("vk_token").remove("vk_chat_id")
                .remove("alert_chat_id").commit();
        backend = new AndroidCredentialBackend(context, c -> secure);
    }

    @Test
    public void snapshotReadsKeysAndTypes() {
        secure.edit().putString("vk_token", "SYNTH_TKN_a1")
                .putLong("vk_chat_id", 5L)
                .putLong("alert_chat_id", -2L).commit();
        Source s = backend.snapshot(Role.ENCRYPTED);
        assertEquals(Read.READABLE, s.read);
        assertEquals(Entry.Kind.PRESENT, s.token.kind);
        assertEquals("SYNTH_TKN_a1", s.token.string);
        assertEquals(5L, s.chatId.number);
        assertEquals(-2L, s.alertChatId.number);
    }

    @Test
    public void absentKeysAndEmptyStringAreDifferent() {
        secure.edit().putString("vk_token", "   ").commit();
        Source s = backend.snapshot(Role.ENCRYPTED);
        assertEquals(Entry.Kind.EMPTY, s.token.kind); // ключ есть, пустой
        assertEquals(Entry.Kind.ABSENT, s.chatId.kind);
        assertEquals(Entry.Kind.ABSENT, s.alertChatId.kind);
    }

    @Test
    public void wrongTypeIsNotConverted() {
        // Числовая строка/float в long автоматически не преобразуется.
        defaults.edit().putString("vk_chat_id", "42").commit();
        Source s = backend.snapshot(Role.DEFAULT);
        assertEquals(Entry.Kind.WRONG_TYPE, s.chatId.kind);
    }

    @Test
    public void keysetRecordsDoNotEnterSnapshot() {
        secure.edit()
                .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                        "KEYSET_DATA")
                .putString("vk_token", "SYNTH_TKN_a2").commit();
        Source s = backend.snapshot(Role.ENCRYPTED);
        assertEquals("SYNTH_TKN_a2", s.token.string);
        assertEquals(Entry.Kind.ABSENT, s.chatId.kind);
        // keyset-запись осталась на месте, но в снимке её нет
        assertTrue(secure.contains(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__"));
    }

    @Test
    public void encryptedOpenFailureIsUnavailableNotPlaintext() {
        AndroidCredentialBackend failing =
                new AndroidCredentialBackend(context,
                        c -> { throw new RuntimeException("keystore down"); });
        Source s = failing.snapshot(Role.ENCRYPTED);
        assertEquals(Read.UNAVAILABLE, s.read);
        assertEquals(Role.ENCRYPTED, s.role); // не подменён plain/default
    }

    @Test
    public void defaultReadsOnlyThreeLegacyKeys() {
        defaults.edit().putString("vk_token", "SYNTH_TKN_a3")
                .putLong("vk_chat_id", 9L)
                .putString("delivery_state_v2", "QUEUE").commit();
        Source s = backend.snapshot(Role.DEFAULT);
        assertEquals("SYNTH_TKN_a3", s.token.string);
        assertEquals(9L, s.chatId.number);
        assertTrue(defaults.contains("delivery_state_v2"));
    }

    @Test
    public void removeDeletesOnlyThreeKeys() {
        plain.edit().putString("vk_token", "SYNTH_TKN_a4")
                .putLong("vk_chat_id", 3L)
                .putLong("alert_chat_id", -1L)
                .putString("unrelated", "KEEP").commit();
        assertTrue(backend.removeCredentials(Role.PLAIN));
        assertFalse(plain.contains("vk_token"));
        assertFalse(plain.contains("vk_chat_id"));
        assertFalse(plain.contains("alert_chat_id"));
        assertEquals("KEEP", plain.getString("unrelated", ""));
    }

    @Test
    public void putCredentialsCommitsAllThree() {
        assertTrue(backend.putCredentials("SYNTH_TKN_a5", 8L, -4L));
        assertEquals("SYNTH_TKN_a5", secure.getString("vk_token", ""));
        assertEquals(8L, secure.getLong("vk_chat_id", 0));
        assertEquals(-4L, secure.getLong("alert_chat_id", 0));
    }
}
