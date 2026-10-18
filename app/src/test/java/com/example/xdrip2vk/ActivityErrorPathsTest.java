package com.example.xdrip2vk;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

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

/**
 * Проверка настоящих путей Activity: список бесед (VkConversationsActivity)
 * и импорт токена (SettingsActivity.SettingsFragment). Маркеры в ответах VK
 * и сообщениях исключений не должны попадать в Logcat и Toast. Сети нет —
 * транспорт VK подменён transportOverride (сбрасывается после теста).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ActivityErrorPathsTest {

    private static final String TOKEN_MARK = "SYNTH_TKN_act9z";
    private static final String MSG_MARK = "SYNTH_ERRMSG_p4m";
    private static final String TITLE_MARK = "SYNTH_CHATTITLE_k8w";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ShadowLog.clear();
        TokenStorage.setToken(context, TOKEN_MARK);
    }

    @After
    public void tearDown() {
        VkApi.transportOverride = null;
        ShadowLog.clear();
    }

    private List<String> allLogs() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem i : ShadowLog.getLogs()) {
            out.add(i.msg);
        }
        return out;
    }

    /** Дождаться Toast из фонового потока Activity и вернуть его текст. */
    private String awaitToast() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            CharSequence t = ShadowToast.getTextOfLatestToast();
            if (t != null) {
                return t.toString();
            }
            Thread.sleep(10);
        }
        return null;
    }

    private VkConversationsActivity startConversations() {
        Intent i = new Intent(context, VkConversationsActivity.class);
        i.putExtra("chat_type", "normal");
        return Robolectric.buildActivity(VkConversationsActivity.class, i)
                .create().get();
    }

    @Test
    public void vkErrorBodyNotShownInToastOrLog() throws Exception {
        String body = "{\"error\":{\"error_code\":10,"
                + "\"error_msg\":\"bearer " + TOKEN_MARK + " " + MSG_MARK + "\"}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        startConversations();
        String toast = awaitToast();

        assertNotNull("ожидался Toast об ошибке", toast);
        assertTrue(toast.contains("бесед") || toast.contains("авторизац"));
        assertFalse(toast.contains(TOKEN_MARK));
        assertFalse(toast.contains(MSG_MARK));
        for (String m : allLogs()) {
            assertFalse(m.contains(TOKEN_MARK));
            assertFalse(m.contains(MSG_MARK));
        }
    }

    @Test
    public void authErrorShowsFixedAuthMessage() throws Exception {
        String body = "{\"error\":{\"error_code\":5,\"error_msg\":\""
                + MSG_MARK + "\"}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        startConversations();
        String toast = awaitToast();

        assertNotNull(toast);
        assertTrue("код 5 → понятное сообщение об авторизации: " + toast,
                toast.contains("авторизац"));
        assertFalse(toast.contains(MSG_MARK));
    }

    @Test
    public void malformedResponseDoesNotLeakToToastOrStacktrace()
            throws Exception {
        String frag = "SYNTH_JSONFRAG_d2q";
        VkApi.transportOverride =
                (m, t, p) -> new VkApi.RawResponse(200, "[[[ " + frag);

        startConversations();
        String toast = awaitToast();

        assertNotNull(toast);
        assertEquals("Некорректный ответ VK", toast);
        for (String m : allLogs()) {
            assertFalse("фрагмент ответа не в логе: " + m, m.contains(frag));
        }
    }

    @Test
    public void conversationTitlesStillDisplayed() throws Exception {
        String body = "{\"response\":{\"count\":1,\"items\":[{"
                + "\"conversation\":{\"peer\":{\"id\":2000000001,"
                + "\"type\":\"chat\"},\"chat_settings\":{\"title\":\""
                + TITLE_MARK + "\"}}}]}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        VkConversationsActivity a = startConversations();
        // Ждём заполнения списка, затем проверяем адаптер — реальный путь.
        for (int i = 0; i < 300; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (a.findViewById(android.R.id.list) != null
                    && ((android.widget.ListView)
                            a.findViewById(R.id.list_conversations))
                            .getAdapter() != null) {
                break;
            }
            Thread.sleep(10);
        }
        android.widget.ListView lv = a.findViewById(R.id.list_conversations);
        assertNotNull(lv.getAdapter());
        assertTrue(lv.getAdapter().getCount() > 0);
        assertTrue(lv.getAdapter().getItem(0).toString().contains(TITLE_MARK));
    }

    @Test
    public void importFileErrorShowsFixedMessage() {
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
        SettingsActivity.SettingsFragment fragment =
                (SettingsActivity.SettingsFragment) activity
                        .getSupportFragmentManager()
                        .findFragmentById(R.id.settings_container);
        assertNotNull(fragment);

        fragment.onTokenFileSelected(uri);
        shadowOf(Looper.getMainLooper()).idle();

        CharSequence toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("ожидался Toast об ошибке чтения", toast);
        assertEquals("Ошибка чтения файла", toast.toString());
        assertFalse(toast.toString().contains(mark));
        for (String m : allLogs()) {
            assertFalse(m.contains(mark));
        }
    }
}
