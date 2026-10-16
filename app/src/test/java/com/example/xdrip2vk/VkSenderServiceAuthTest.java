package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.SendResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * №2Б.2: восстановление авторизации и привязка очереди к отправителю —
 * на настоящем коде сервиса. Сеть и проверка владельца подменены,
 * токены синтетические.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceAuthTest {

    private Context context;
    private SharedPreferences prefs;
    private List<int[]> rids;
    private List<String> peers;
    private AtomicReference<SendResult> result;
    private java.util.function.Function<String, SendResult> rule;
    private java.util.function.Function<String, String> verifier;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_1");
        TokenStorage.setChatId(context, 777);
        rids = new ArrayList<>();
        peers = new ArrayList<>();
        result = new AtomicReference<>(SendResult.SENT);
        rule = null;
        verifier = (token) -> "user:1";
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(new int[]{rid});
            return rule != null ? rule.apply(peer) : result.get();
        };
        VkSenderService.identityVerifierOverride = verifier == null ? null
                : (token) -> verifier.apply(token);
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class).create().get();
        Shadows.shadowOf(s.workerLooperForTest()).idle();
        return s;
    }

    private void idle(VkSenderService s) {
        Shadows.shadowOf(s.workerLooperForTest()).idle();
    }

    private Intent autoUpdate(double bg, long ts) {
        Intent i = new Intent(context, VkSenderService.class);
        i.putExtra("auto_update", true);
        i.putExtra("bg_value", bg);
        i.putExtra("timestamp", ts);
        i.putExtra("direction", "Flat");
        return i;
    }

    private Intent tokenChanged() {
        return new Intent(context, VkSenderService.class)
                .setAction(VkSenderService.ACTION_TOKEN_CHANGED);
    }

    private String lastNotificationText(VkSenderService s) {
        CharSequence t = s.notificationForTest();
        return t == null ? "" : t.toString();
    }

    // --- матрица авторизации через настоящий сервис ---

    @Test
    public void authFailedResumesAfterSameSenderTokenChange() {
        VkSenderService s = startService();
        // обычное сообщение получает AUTH_FAILED и остаётся в очереди
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        int rid = rids.get(0)[0];
        assertNotEquals(0, rid);
        assertTrue(lastNotificationText(s).contains("авторизации"));

        // с тем же токеном повторных попыток нет — и без посекундного цикла
        s.onStartCommand(autoUpdate(121, System.currentTimeMillis() - 30_000), 0, 2);
        idle(s);
        assertEquals(1, peers.size());
        assertTrue("посекундный цикл: " + s.lastDrainDelayMs,
                s.lastDrainDelayMs > 60_000);

        // новый токен того же отправителя — очередь возобновляется сразу,
        // не дожидаясь показания; random_id прежний
        rule = null; // SENT
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_2");
        s.onStartCommand(tokenChanged(), 0, 3);
        idle(s);
        assertEquals(3, peers.size()); // auth-failed + сообщение 2-го показания
        assertEquals(rid, rids.get(1)[0]); // возобновлено с прежним random_id
        s.onDestroy();
    }

    @Test
    public void foreignSenderDoesNotReceiveOldQueueAndChatsReset() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());

        // новый токен подтверждён для ДРУГОГО аккаунта
        verifier = (token) -> "user:2";
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_FOREIGN");
        s.onStartCommand(tokenChanged(), 0, 2);
        idle(s);

        assertEquals("старое сообщение ушло чужому аккаунту: " + peers,
                1, peers.size());
        // чаты сброшены — новый отправитель должен выбрать их заново
        assertEquals(0, TokenStorage.getChatId(context));
        // причина блокировки показана
        String text = lastNotificationText(s);
        assertTrue("ожидалась причина блокировки: " + text,
                text.contains("заблокирован") || text.contains("авторизац"));
        s.onDestroy();
    }

    @Test
    public void unverifiableTokenKeepsQueueBlocked() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());

        // проверка личности не удалась: timeout/битый ответ/отказ — не успех
        verifier = (token) -> null;
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_BAD");
        s.onStartCommand(tokenChanged(), 0, 2);
        idle(s);
        assertEquals(1, peers.size()); // отправок нет
        String text = lastNotificationText(s);
        assertTrue("ожидался статус неподтверждённого владельца: " + text,
                text.contains("владельца токена"));
        s.onDestroy();
    }

    @Test
    public void staleVerifyResultForReplacedTokenIgnored() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());

        // проверка T1 возвращает user:9, но во время неё токен уже заменён на T2
        verifier = (token) -> {
            if (token.equals("SYNTHETIC_TOKEN_STALE")) {
                TokenStorage.setToken(context, "SYNTHETIC_TOKEN_2");
                return "user:9"; // результат старой версии — игнорируется
            }
            return "user:1"; // T2 подтверждает того же отправителя
        };
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_STALE");
        s.onStartCommand(tokenChanged(), 0, 2);
        idle(s);
        // очередь возобновилась под T2 (user:1), не под "user:9" старого ответа
        assertEquals(2, peers.size());
        assertEquals("user:1", prefs.getString("confirmed_sender", ""));
        s.onDestroy();
    }

    @Test
    public void legacyQueueWithoutOwnerStaysBlocked() {
        // очередь старой схемы без владельца — записана до запуска сервиса
        long exp = System.currentTimeMillis() + 10 * 60_000;
        prefs.edit().putString("delivery_state_v2",
                "{\"v\":1,\"outbox\":{\"messages\":[{"
                        + "\"key\":\"bg:1\",\"peer\":\"777\",\"text\":\"legacy\","
                        + "\"rid\":555,\"exp\":" + exp + ",\"att\":0,\"nb\":0,"
                        + "\"term\":false,\"err\":\"\"}]}}").commit();
        VkSenderService s = startService();
        idle(s);
        // старое сообщение новому подтверждённому аккаунту не приписано
        assertTrue("legacy-сообщение отправлено: " + peers, peers.isEmpty());
        String text = lastNotificationText(s);
        assertTrue("ожидалась причина блокировки: " + text,
                text.contains("заблокирован"));
        s.onDestroy();
    }

    @Test
    public void sameHashCodeTokensAreDistinct() {
        // "Aa" и "BB" дают одинаковый String.hashCode — fingerprint различает
        assertEquals("Aa".hashCode(), "BB".hashCode());
        TokenStorage.setToken(context, "Aa");
        VkSenderService s = startService();
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());

        // токен "BB" того же отправителя — блокировка "Aa" ему не мешает
        rule = null;
        TokenStorage.setToken(context, "BB");
        s.onStartCommand(tokenChanged(), 0, 2);
        idle(s);
        assertEquals(2, peers.size());
        s.onDestroy();
    }
}
