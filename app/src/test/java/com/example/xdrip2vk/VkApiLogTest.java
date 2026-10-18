package com.example.xdrip2vk;

import static org.junit.Assert.*;

import com.example.xdrip2vk.core.SendResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Безопасные журналы VkApi: настоящий sendMessage с подменённым транспортом
 * (без сети). Маркеры токена/текста/peer_id/названия — синтетические и
 * уникальные; проверяется их ОТСУТСТВИЕ в Logcat и сохранение безопасных
 * полей (HTTP-код, SendResult, числовой error_code).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkApiLogTest {

    private static final String TOKEN_MARK = "SYNTH_TKN_mrkr9a1";
    private static final String TEXT_MARK = "SYNTH_MEDTEXT_q7z";
    private static final String PEER_MARK = "777555111";

    @Before
    public void setUp() {
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        VkApi.transportOverride = null; // подмена сбрасывается после теста
        ShadowLog.clear();
    }

    private List<String> allMessages() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem i : ShadowLog.getLogs()) {
            out.add(i.msg);
        }
        return out;
    }

    private void assertNoMarkers(List<String> msgs) {
        for (String m : msgs) {
            assertFalse("токен не попадает в лог: " + m, m.contains(TOKEN_MARK));
            assertFalse("текст сообщения не попадает в лог: " + m,
                    m.contains(TEXT_MARK));
            assertFalse("peer_id не попадает в лог: " + m,
                    m.contains(PEER_MARK));
            assertFalse("request_params не выводятся: " + m,
                    m.contains("request_params"));
        }
    }

    @Test
    public void apiErrorWithRequestParamsNotLogged() {
        String body = "{\"error\":{\"error_code\":113,"
                + "\"error_msg\":\"Invalid " + TOKEN_MARK + "\","
                + "\"request_params\":["
                + "{\"key\":\"access_token\",\"value\":\"" + TOKEN_MARK + "\"},"
                + "{\"key\":\"peer_id\",\"value\":\"" + PEER_MARK + "\"},"
                + "{\"key\":\"message\",\"value\":\"" + TEXT_MARK + "\"}]}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        SendResult r = VkApi.sendMessage(TOKEN_MARK, PEER_MARK, TEXT_MARK, 42);

        assertEquals(SendResult.PERMANENT, r); // классификация сохранена
        List<String> msgs = allMessages();
        assertFalse(msgs.isEmpty());
        assertNoMarkers(msgs);
        // Безопасные поля присутствуют.
        boolean hasFields = false;
        for (String m : msgs) {
            if (m.contains("http=200") && m.contains("PERMANENT")
                    && m.contains("error_code=113")) {
                hasFields = true;
            }
        }
        assertTrue("http-код, SendResult и error_code в логе: " + msgs,
                hasFields);
    }

    @Test
    public void authErrorCodeExtractedWithoutBody() {
        String body = "{\"error\":{\"error_code\":5,"
                + "\"error_msg\":\"" + TOKEN_MARK + " " + TEXT_MARK + "\"}}";
        VkApi.transportOverride = (m, t, p) -> new VkApi.RawResponse(200, body);

        SendResult r = VkApi.sendMessage(TOKEN_MARK, PEER_MARK, TEXT_MARK, 7);

        assertEquals(SendResult.AUTH_FAILED, r);
        List<String> msgs = allMessages();
        assertNoMarkers(msgs);
        boolean hasCode = false;
        for (String m : msgs) {
            if (m.contains("error_code=5")) {
                hasCode = true;
            }
        }
        assertTrue(hasCode);
    }

    @Test
    public void brokenJsonDoesNotLeakFragment() {
        String frag = "SYNTH_BROKEN_x1";
        VkApi.transportOverride =
                (m, t, p) -> new VkApi.RawResponse(200, "{{{ " + frag);

        SendResult r = VkApi.sendMessage(TOKEN_MARK, PEER_MARK, TEXT_MARK, 9);

        assertEquals(SendResult.RETRYABLE, r);
        for (String msg : allMessages()) {
            assertFalse("фрагмент битого JSON не в логе: " + msg,
                    msg.contains(frag));
            assertFalse(msg.contains(TOKEN_MARK));
        }
    }

    @Test
    public void transportExceptionMessageNotLogged() {
        String mark = "SYNTH_EXMSG_k3d";
        VkApi.transportOverride = (m, t, p) -> {
            throw new RuntimeException("io failure " + mark);
        };

        SendResult r = VkApi.sendMessage(TOKEN_MARK, PEER_MARK, TEXT_MARK, 3);

        assertEquals(SendResult.RETRYABLE, r);
        for (String msg : allMessages()) {
            assertFalse("getMessage() исключения не в логе: " + msg,
                    msg.contains(mark));
        }
    }
}
