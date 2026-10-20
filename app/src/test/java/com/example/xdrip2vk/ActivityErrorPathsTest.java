package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.widget.ListView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Проверка настоящих путей Activity: список бесед (VkConversationsActivity)
 * и импорт токена (SettingsActivity.SettingsFragment). Маркеры в ответах VK
 * и сообщениях исключений не должны попадать в Logcat и Toast. Сети нет —
 * транспорт VK подменён transportOverride.
 *
 * Синхронизация без таймерных опросов: loadDoneForTest отсчитывается
 * фоновым потоком после постановки результата в главный поток; тест ждёт
 * latch с ограниченным timeout (явное падение при превышении), затем
 * прокачивает главный looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ActivityErrorPathsTest {

    private static final String TOKEN_MARK = "SYNTH_TKN_act9z";
    private static final String MSG_MARK = "SYNTH_ERRMSG_p4m";
    private static final String TITLE_MARK = "SYNTH_CHATTITLE_k8w";
    private static final long AWAIT_MS = 10_000;

    private Context context;
    private final List<Activity> opened = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        VkApi.transportOverride = null; // подмена прошлого теста не протекает
        ShadowLog.clear();
        ShadowToast.reset();
        VkConversationsActivity.loadDoneForTest = null;
        CredHelper.setToken(context, TOKEN_MARK); // изолированный синтетический токен
    }

    @After
    public void tearDown() {
        // Незавершённая фоновая задача — явная ошибка cleanup; подмены
        // сбрасываются в finally в любом случае.
        try {
            awaitQuietly();
        } finally {
            for (Activity a : opened) {
                if (!a.isFinishing() && !a.isDestroyed()) {
                    a.finish();
                }
            }
            VkApi.transportOverride = null;
            VkConversationsActivity.loadDoneForTest = null;
            ShadowLog.clear();
            ShadowToast.reset();
        }
    }

    private void awaitQuietly() {
        CountDownLatch l = VkConversationsActivity.loadDoneForTest;
        if (l == null) {
            return;
        }
        try {
            assertTrue("cleanup: фоновая задача не завершилась",
                    l.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("cleanup: ожидание завершения прервано");
        }
    }

    /** Ждать завершения фоновой загрузки и выполнить результат на главном. */
    private void awaitLoadAndIdle() throws InterruptedException {
        CountDownLatch l = VkConversationsActivity.loadDoneForTest;
        assertNotNull("latch загрузки не установлен тестом", l);
        assertTrue("фоновая загрузка не завершилась за " + AWAIT_MS + " мс",
                l.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String latestToast() {
        CharSequence t = ShadowToast.getTextOfLatestToast();
        return t == null ? null : t.toString();
    }

    private List<String> allLogTexts() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem i : ShadowLog.getLogs()) {
            out.add(i.msg);
            // Утечка возможна и через Throwable/цепочку причин.
            Throwable th = i.throwable;
            while (th != null) {
                out.add(th.getClass().getName() + ":" + th.getMessage());
                th = th.getCause();
            }
        }
        return out;
    }

    private void assertNoMarkersInLogs(String... marks) {
        for (String m : allLogTexts()) {
            for (String mark : marks) {
                assertFalse("маркер в логе/throwable: " + m,
                        m != null && m.contains(mark));
            }
        }
    }

    private VkConversationsActivity startConversations() {
        VkConversationsActivity.loadDoneForTest = new CountDownLatch(1);
        Intent i = new Intent(context, VkConversationsActivity.class);
        i.putExtra("chat_type", "normal");
        VkConversationsActivity a = Robolectric
                .buildActivity(VkConversationsActivity.class, i)
                .create().get();
        opened.add(a);
        return a;
    }

    @Test
    public void vkErrorBodyNotShownInToastOrLog() throws Exception {
        String body = "{\"error\":{\"error_code\":10,"
                + "\"error_msg\":\"bearer " + TOKEN_MARK + " " + MSG_MARK + "\"}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        startConversations();
        awaitLoadAndIdle();

        String toast = latestToast();
        assertNotNull("ожидался Toast об ошибке", toast);
        assertTrue(toast.contains("бесед") || toast.contains("авторизац"));
        assertFalse(toast.contains(TOKEN_MARK));
        assertFalse(toast.contains(MSG_MARK));
        assertNoMarkersInLogs(TOKEN_MARK, MSG_MARK);
    }

    @Test
    public void authErrorShowsFixedAuthMessage() throws Exception {
        String body = "{\"error\":{\"error_code\":5,\"error_msg\":\""
                + MSG_MARK + "\"}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        startConversations();
        awaitLoadAndIdle();

        String toast = latestToast();
        assertNotNull(toast);
        assertTrue("код 5 → понятное сообщение об авторизации: " + toast,
                toast.contains("авторизац"));
        assertFalse(toast.contains(MSG_MARK));
        assertNoMarkersInLogs(MSG_MARK);
    }

    @Test
    public void malformedResponseDoesNotLeakToToastOrStacktrace()
            throws Exception {
        String frag = "SYNTH_JSONFRAG_d2q";
        VkApi.transportOverride =
                (m, t, p) -> new VkApi.RawResponse(200, "[[[ " + frag);

        startConversations();
        awaitLoadAndIdle();

        assertEquals("Некорректный ответ VK", latestToast());
        assertNoMarkersInLogs(frag);
    }

    @Test
    public void conversationTitlesStillDisplayed() throws Exception {
        String body = "{\"response\":{\"count\":1,\"items\":[{"
                + "\"conversation\":{\"peer\":{\"id\":2000000001,"
                + "\"type\":\"chat\"},\"chat_settings\":{\"title\":\""
                + TITLE_MARK + "\"}}}]}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        VkConversationsActivity a = startConversations();
        awaitLoadAndIdle();

        ListView lv = a.findViewById(R.id.list_conversations);
        assertNotNull(lv.getAdapter());
        assertEquals(1, lv.getAdapter().getCount());
        assertTrue(lv.getAdapter().getItem(0).toString()
                .contains(TITLE_MARK));
    }

    @Test
    public void importFileErrorShowsFixedMessage() throws Exception {
        // Настоящий путь: фрагмент настроек из SettingsActivity, контент
        // ContentResolver бросает IOException с маркером в сообщении.
        String mark = "SYNTH_FILEFRAG_m6r";
        Uri uri = Uri.parse("content://fake.test/import.txt");
        shadowOf(context.getContentResolver())
                .registerInputStream(uri, new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("fs error " + mark);
                    }
                });

        SettingsActivity activity = Robolectric
                .buildActivity(SettingsActivity.class).create().start()
                .resume().get();
        opened.add(activity);
        SettingsActivity.SettingsFragment fragment =
                (SettingsActivity.SettingsFragment) activity
                        .getSupportFragmentManager()
                        .findFragmentById(R.id.settings_container);
        assertNotNull(fragment);

        SettingsActivity.SettingsFragment.importDoneForTest =
                new CountDownLatch(1);
        fragment.onTokenFileSelected(uri);
        try {
            assertTrue("фоновое чтение файла не завершилось",
                    SettingsActivity.SettingsFragment.importDoneForTest
                            .await(AWAIT_MS, TimeUnit.MILLISECONDS));
        } finally {
            SettingsActivity.SettingsFragment.importDoneForTest = null;
        }
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Ошибка чтения файла", latestToast());
        assertNoMarkersInLogs(mark);
    }

    // ==================== 2Г.4А.1: исход команды vs состояние ====================

    private VkConversationsActivity startConversationsWithChat(String type)
            throws Exception {
        String body = "{\"response\":{\"count\":1,\"items\":[{"
                + "\"conversation\":{\"peer\":{\"id\":2000000001,"
                + "\"type\":\"chat\"},\"chat_settings\":{\"title\":\""
                + TITLE_MARK + "\"}}}]}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);
        VkConversationsActivity.loadDoneForTest = new CountDownLatch(1);
        Intent i = new Intent(context, VkConversationsActivity.class);
        i.putExtra("chat_type", type);
        VkConversationsActivity a = Robolectric
                .buildActivity(VkConversationsActivity.class, i)
                .create().get();
        opened.add(a);
        awaitLoadAndIdle();
        return a;
    }

    private void clickFirstConversation(VkConversationsActivity a) {
        ListView lv = a.findViewById(R.id.list_conversations);
        lv.performItemClick(lv.getAdapter().getView(0, null, lv), 0,
                lv.getAdapter().getItemId(0));
    }

    /**
     * Точное воспроизведение: список загружен с токеном A, токен сменился
     * на B, выбор отклонён STALE_TOKEN — callback уже ждёт в main-очереди,
     * когда resolve публикует READY(B). Задержанный callback не объявляет
     * выбор успешным и не закрывает экран.
     */
    @Test
    public void staleSelectionAfterTokenChangeShowsNoSuccess() throws Exception {
        VkConversationsActivity a = startConversationsWithChat("normal");
        // Токен сменился после загрузки списка — fingerprint устарел.
        assertEquals(com.example.xdrip2vk.store.CredentialStore.Status.READY,
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_B")
                        .get(AWAIT_MS, TimeUnit.MILLISECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        clickFirstConversation(a);
        // Команда исполнена и отклонена; её callback уже в main-очереди.
        Credentials.get(context).awaitPublishIdleForTest();
        // Более новое чтение публикует READY(B).
        Credentials.get(context).resolveAsync()
                .get(AWAIT_MS, TimeUnit.MILLISECONDS);
        Credentials.get(context).awaitPublishIdleForTest();

        shadowOf(Looper.getMainLooper()).idle();

        String toast = latestToast();
        assertNotNull("ожидался Toast с причиной", toast);
        assertTrue("устаревший токен — просьба загрузить список заново: "
                + toast, toast.contains("изменился"));
        assertFalse("ложного «Выбрано» нет", toast.contains("Выбрано"));
        assertFalse("экран не закрыт как при успехе", a.isFinishing());
        assertEquals("запись не выполнена — адресат не изменён", 0,
                CredHelper.getChatId(context));
    }

    /** Аналогичный сценарий для тревожного адресата. */
    @Test
    public void staleAlertSelectionShowsNoSuccess() throws Exception {
        VkConversationsActivity a = startConversationsWithChat("alert");
        assertEquals(com.example.xdrip2vk.store.CredentialStore.Status.READY,
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_B")
                        .get(AWAIT_MS, TimeUnit.MILLISECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();

        clickFirstConversation(a);
        Credentials.get(context).awaitPublishIdleForTest();
        Credentials.get(context).resolveAsync()
                .get(AWAIT_MS, TimeUnit.MILLISECONDS);
        Credentials.get(context).awaitPublishIdleForTest();
        shadowOf(Looper.getMainLooper()).idle();

        String toast = latestToast();
        assertNotNull(toast);
        assertTrue(toast.contains("изменился"));
        assertFalse(toast.contains("Выбрано"));
        assertFalse(a.isFinishing());
        assertEquals(0, CredHelper.getAlertChatId(context));
    }

    /**
     * Подтверждённый выбор, замещённый более новым изменением токена до
     * доставки callback: интерфейс не подтверждает старое значение.
     */
    @Test
    public void supersededSelectionNotReportedAsChosen() throws Exception {
        VkConversationsActivity a = startConversationsWithChat("normal");
        clickFirstConversation(a); // commit быстрый, callback в очереди
        Credentials.get(context).awaitPublishIdleForTest();
        // Более новое изменение принято до исполнения callback.
        assertEquals(com.example.xdrip2vk.store.CredentialStore.Status.READY,
                Credentials.get(context).setTokenAsync("SYNTH_TOKEN_C")
                        .get(AWAIT_MS, TimeUnit.MILLISECONDS).status);
        Credentials.get(context).awaitPublishIdleForTest();
        shadowOf(Looper.getMainLooper()).idle();

        String toast = latestToast();
        assertFalse("запоздавший успех не подтверждает старый выбор",
                toast != null && toast.contains("Выбрано"));
        assertFalse("экран не закрыт как при успехе", a.isFinishing());
        assertEquals("SYNTH_TOKEN_C", CredHelper.getToken(context));
    }

    /** Обычный успешный выбор беседы после commit=true работает. */
    @Test
    public void successfulSelectionShowsChosen() throws Exception {
        VkConversationsActivity a = startConversationsWithChat("normal");
        clickFirstConversation(a);
        Credentials.get(context).awaitPublishIdleForTest();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Выбрано: " + TITLE_MARK, latestToast());
        assertTrue(a.isFinishing());
        assertEquals(2000000001L, CredHelper.getChatId(context));
    }

    /**
     * Неподтверждённая запись токена (commit=false) не получает
     * «Токен сохранён» от последующего READY — callback несёт исход
     * своей команды.
     */
    @Test
    public void uncommittedTokenWriteGetsNoSavedToast() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean failOnce =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        CredHelper.HookedBackend hb = new CredHelper.HookedBackend(
                new com.example.xdrip2vk.store.AndroidCredentialBackend(
                        context, c -> CredHelper.enc(context)));
        hb.putOverride = (t, c, a, st) -> !failOnce.getAndSet(false)
                && hb.d.putCredentials(t, c, a, st);
        com.example.xdrip2vk.store.CredentialStore
                .resetCoordinatorForTest(hb);
        Credentials.resetForTest();
        Credentials.backendForTest = hb;
        Credentials.permitMainBlockingForTest = true;

        SettingsActivity activity = Robolectric
                .buildActivity(SettingsActivity.class).create().start()
                .resume().get();
        opened.add(activity);
        SettingsActivity.SettingsFragment fragment =
                (SettingsActivity.SettingsFragment) activity
                        .getSupportFragmentManager()
                        .findFragmentById(R.id.settings_container);
        assertNotNull(fragment);

        // Ручной ввод токена — реальный путь OnPreferenceChangeListener.
        androidx.preference.EditTextPreference tokenPref =
                fragment.findPreference("vk_token");
        assertNotNull(tokenPref);
        tokenPref.callChangeListener("SYNTH_TOKEN_UI1");
        // Запись отклонена commit=false; callback в main-очереди.
        Credentials.get(context).awaitPublishIdleForTest();
        // Последующее чтение повторяет commit → READY опубликован ДО
        // исполнения callback старой команды.
        Credentials.get(context).resolveAsync()
                .get(AWAIT_MS, TimeUnit.MILLISECONDS);
        Credentials.get(context).awaitPublishIdleForTest();
        shadowOf(Looper.getMainLooper()).idle();

        String toast = latestToast();
        assertNotNull("ожидался Toast с причиной отказа", toast);
        assertFalse("неподтверждённая запись не получила «сохранён»",
                toast.contains("Токен сохранён"));
        assertTrue("причина — незавершённая операция: " + toast,
                toast.contains("не завершена") || toast.contains("Не удалось"));
    }

    /** Обычное успешное сохранение токена после commit=true работает. */
    @Test
    public void successfulTokenEntryShowsSaved() throws Exception {
        SettingsActivity activity = Robolectric
                .buildActivity(SettingsActivity.class).create().start()
                .resume().get();
        opened.add(activity);
        SettingsActivity.SettingsFragment fragment =
                (SettingsActivity.SettingsFragment) activity
                        .getSupportFragmentManager()
                        .findFragmentById(R.id.settings_container);
        assertNotNull(fragment);
        androidx.preference.EditTextPreference tokenPref =
                fragment.findPreference("vk_token");
        assertNotNull(tokenPref);

        tokenPref.callChangeListener("SYNTH_TOKEN_UI2");
        Credentials.get(context).awaitPublishIdleForTest();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Токен сохранён", latestToast());
        assertEquals("SYNTH_TOKEN_UI2", CredHelper.getToken(context));
    }
}
