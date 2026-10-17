package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Воспроизведение дефектов хранения токена (задание 2Г.1) настоящим кодом
 * TokenStorage. Под Robolectric AndroidKeyStore отсутствует («AndroidKeyStore
 * not found») — EncryptedSharedPreferences всегда падает в plaintext-фолбэк,
 * что само по себе воспроизводит дефект молчаливой записи без шифрования.
 * Проверки с работающим Keystore (encrypted-файл, повреждение ключа,
 * перенос на другое устройство) здесь невозможны — отмечены в отчёте.
 * Все значения синтетические.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TokenStorageDefectTest {

    private Context context;
    private SharedPreferences defaultPrefs;
    private SharedPreferences plainPrefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        plainPrefs = context.getSharedPreferences("secure_tokens_plain",
                Context.MODE_PRIVATE);
        defaultPrefs.edit().clear().commit();
        plainPrefs.edit().clear().commit();
    }

    /**
     * Дефект 1: при недоступном EncryptedSharedPreferences setToken пишет
     * токен в secure_tokens_plain БЕЗ шифрования и без информирования
     * пользователя. getToken читает его обратно — сбой незаметен.
     */
    @Test
    public void encryptedUnavailableWritesPlaintextSilently() {
        TokenStorage.setToken(context, "SYNTH_TOKEN_AAA");
        assertEquals("SYNTH_TOKEN_AAA", TokenStorage.getToken(context));

        assertTrue("токен попал в plaintext-файл",
                plainPrefs.contains("vk_token"));
        assertEquals("значение в plaintext читается без шифрования",
                "SYNTH_TOKEN_AAA", plainPrefs.getString("vk_token", ""));
        // Файл на диске существует — токен доступен любому, кто читает
        // shared_prefs (root, backup без исключений).
        File f = new File(context.getFilesDir().getParent(),
                "shared_prefs/secure_tokens_plain.xml");
        assertTrue(f.exists());
    }

    /**
     * Дефект 2: миграция из default prefs молчаливо перезаписывает УЖЕ
     * существующее в целевом хранилище значение — конфликт разных токенов
     * не детектируется и не решается пользователем.
     */
    @Test
    public void migrateOnceOverwritesDifferentTargetSilently() {
        // В целевом хранилище уже есть токен (например, записанный ранее).
        plainPrefs.edit().putString("vk_token", "SYNTH_TOKEN_BBB").commit();
        // А в default prefs остался старый (legacy-схема).
        defaultPrefs.edit().putString("vk_token", "SYNTH_TOKEN_CCC").commit();

        String token = TokenStorage.getToken(context);
        assertEquals("миграция молча перезаписала целевое значение",
                "SYNTH_TOKEN_CCC", token);
        assertEquals("SYNTH_TOKEN_CCC",
                plainPrefs.getString("vk_token", ""));
        assertFalse("источник удалён",
                defaultPrefs.contains("vk_token"));
    }

    /**
     * Дефект 3: миграция переносит токен в то хранилище, которое удалось
     * открыть СЕЙЧАС. При недоступном шифровании legacy-токен из default
     * prefs попадает в plaintext — миграция «в защищённое хранилище»
     * фактически понижает защиту.
     */
    @Test
    public void migrationToPlainLeavesReadableToken() {
        defaultPrefs.edit()
                .putString("vk_token", "SYNTH_TOKEN_LEGACY")
                .putLong("vk_chat_id", 123456789L)
                .putLong("alert_chat_id", 987654321L)
                .commit();

        String token = TokenStorage.getToken(context);
        assertEquals("SYNTH_TOKEN_LEGACY", token);
        assertEquals("токен мигрирован в plaintext",
                "SYNTH_TOKEN_LEGACY",
                plainPrefs.getString("vk_token", ""));
        assertEquals(123456789L, plainPrefs.getLong("vk_chat_id", 0));
        assertEquals(987654321L, plainPrefs.getLong("alert_chat_id", 0));
        assertFalse(defaultPrefs.contains("vk_token"));
        assertFalse(defaultPrefs.contains("vk_chat_id"));
        assertFalse(defaultPrefs.contains("alert_chat_id"));
    }

    /**
     * Дефект 4 (часть, проверяемая кодом): запись в целевое хранилище —
     * apply() без подтверждения, источник удаляется сразу после. Если
     * процесс умирает до сброса apply() на диск — токен потерян без
     * возможности восстановления. Часть с kill-процессом воспроизвести
     * нельзя — зафиксировано как код-анализ.
     */
    @Test
    public void migrationDeletesSourceImmediatelyAfterApply() {
        defaultPrefs.edit().putString("vk_token", "SYNTH_TOKEN_DDD").commit();
        TokenStorage.getToken(context); // триггер миграции
        // apply() в память уже виден — источник при этом удалён.
        // Окно потери: между apply() и фактическим сбросом на диск.
        assertFalse("источник удалён без подтверждения записи",
                defaultPrefs.contains("vk_token"));
        assertEquals("SYNTH_TOKEN_DDD",
                plainPrefs.getString("vk_token", ""));
    }

    /**
     * Проверка вопроса задания: создаёт ли EditTextPreference копию токена
     * в default prefs после TokenStorage.setToken. Продакшн-код ставит
     * setPersistent(false) → копии нет; контроль — без setPersistent(false)
     * preference СОХРАНИЛ бы токен в default prefs.
     */
    @Test
    public void tokenPreferenceDoesNotCopyToDefaultPrefs() {
        androidx.preference.PreferenceManager mgr =
                new androidx.preference.PreferenceManager(context);
        PreferenceScreen screen = mgr.inflateFromResource(
                context, R.xml.preferences, null);
        EditTextPreference tokenPref = screen.findPreference("vk_token");
        assertNotNull(tokenPref);

        // Тот же порядок, что в SettingsActivity: persistent=false → setText.
        tokenPref.setPersistent(false);
        tokenPref.setText("SYNTH_TOKEN_UI");

        assertFalse("копии токена в default prefs нет",
                defaultPrefs.contains("vk_token"));

        // Контроль: persistent preference с тем же ключом писал бы копию.
        EditTextPreference control = new EditTextPreference(context);
        control.setKey("vk_token");
        screen.addPreference(control);
        control.setText("SYNTH_TOKEN_CTRL");
        assertEquals("без setPersistent(false) токен оказался бы в default prefs",
                "SYNTH_TOKEN_CTRL", defaultPrefs.getString("vk_token", ""));
        defaultPrefs.edit().remove("vk_token").commit();
    }

    /**
     * Ручной ввод/импорт пишут только в TokenStorage — при недоступном
     * шифровании единственная постоянная копия токена — plaintext-файл.
     */
    @Test
    public void manualEntryPathLeavesPlaintextCopy() {
        // Путь ручного ввода/импорта: TokenStorage.setToken + notifyTokenChanged.
        TokenStorage.setToken(context, "SYNTH_TOKEN_MANUAL");
        assertEquals("SYNTH_TOKEN_MANUAL",
                plainPrefs.getString("vk_token", ""));
        assertFalse(defaultPrefs.contains("vk_token"));
    }
}
