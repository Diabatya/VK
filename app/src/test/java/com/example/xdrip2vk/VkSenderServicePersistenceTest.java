package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.DeliveryCoordinator;
import com.example.xdrip2vk.core.PendingMessage;
import com.example.xdrip2vk.core.SendResult;
import com.example.xdrip2vk.core.TreatmentEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * №2Б.3: подтверждённый checkpoint до сетевой попытки и упорядоченное
 * завершение сервиса. Сеть и проверка владельца подменены, запись на диск —
 * через RecordingStore с историей успешных записей и возможностью отказа.
 * «Завершение процесса» в тестах моделируется восстановлением координатора
 * из сохранённого снимка — это НЕ реальный kill процесса.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServicePersistenceTest {

    /**
     * Хранилище с историей успешных записей. failNextWrite — одноразовый
     * отказ записи, failWrites — диск отказывает до снятия флага.
     */
    static final class RecordingStore implements VkSenderService.StateStore {
        final List<String> history = new ArrayList<>();
        final List<String> writeThreads = new ArrayList<>();
        boolean failNextWrite;
        boolean failWrites;

        @Override
        public String read() {
            return history.isEmpty() ? "" : history.get(history.size() - 1);
        }

        @Override
        public boolean write(String json) {
            writeThreads.add(Thread.currentThread().getName());
            if (failWrites || failNextWrite) {
                failNextWrite = false;
                return false;
            }
            history.add(json);
            return true;
        }
    }

    private Context context;
    private SharedPreferences prefs;
    private RecordingStore store;
    private List<String> peers;
    private List<Integer> rids;
    private java.util.function.Function<String, SendResult> rule;
    private java.util.function.Function<String, String> verifier;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        TokenStorage.setToken(context, "SYNTHETIC_TOKEN_1");
        TokenStorage.setChatId(context, 777);
        store = new RecordingStore();
        VkSenderService.stateStoreOverride = store;
        peers = new ArrayList<>();
        rids = new ArrayList<>();
        rule = null;
        verifier = (token) -> "user:1";
        VkSenderService.identityVerifierOverride = (token) -> verifier.apply(token);
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            return rule != null ? rule.apply(peer) : SendResult.SENT;
        };
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        VkSenderService.stateStoreOverride = null;
        VkSenderService.bgDataOverride = null;
        VkSenderService.treatmentsOverride = null;
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class).create().get();
        Shadows.shadowOf(s.workerLooperForTest()).idle();
        return s;
    }

    private void idle(VkSenderService s) {
        try {
            android.os.Looper l = s.workerLooperForTest();
            if (l != null) {
                Shadows.shadowOf(l).idle();
            }
        } catch (IllegalStateException e) {
            // looper уже завершён после quitSafely — задач больше нет
        }
    }

    private void idleFor(VkSenderService s, java.time.Duration d) {
        try {
            android.os.Looper l = s.workerLooperForTest();
            if (l != null) {
                Shadows.shadowOf(l).idleFor(d);
            }
        } catch (IllegalStateException e) {
            // looper уже завершён после quitSafely — задач больше нет
        }
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

    // --- checkpoint до/после сетевой попытки ---

    @Test
    public void checkpointBeforeSendContainsSameRandomId() {
        VkSenderService s = startService();
        // во время send последняя успешная запись должна уже содержать этот rid
        final List<Boolean> okAtSend = new ArrayList<>();
        VkSenderService.senderOverride = (peer, text, rid) -> {
            okAtSend.add(!store.history.isEmpty()
                    && store.history.get(store.history.size() - 1).contains("\"rid\":" + rid));
            peers.add(peer);
            rids.add(rid);
            return SendResult.SENT;
        };
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals(1, okAtSend.size());
        assertTrue("до первого send нет записи с этим random_id", okAtSend.get(0));
        s.onDestroy();
    }

    @Test
    public void restoreFromLastCheckpointKeepsMessageAndBindings() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();
        rule = (peer) -> SendResult.RETRYABLE; // оба остаются в очереди
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(2, peers.size());
        s.onDestroy();

        // модель рестарта: координатор восстановлен из последнего успешного checkpoint
        DeliveryCoordinator restored = DeliveryCoordinator.load(store.read());
        assertEquals(2, restored.pendingCount());
        for (PendingMessage m : restored.pendingMessages()) {
            assertEquals("user:1", m.senderId);
            assertEquals("user:1", m.peerOwner);
            assertFalse(m.enqueuedFp.isEmpty());
            assertTrue(m.randomId != 0);
            assertTrue(m.expiresAtMs > 0);
        }
    }

    @Test
    public void sentThenRetryableRestoreSkipsSentKeepsRidAndBackoff() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();
        // обычное SENT, тревога RETRYABLE
        rule = (peer) -> peer.equals("888") ? SendResult.RETRYABLE : SendResult.SENT;
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(2, peers.size());
        int alertRid = rids.get(1);
        s.onDestroy();

        // восстановление из последнего успешного checkpoint
        DeliveryCoordinator restored = DeliveryCoordinator.load(store.read());
        assertEquals(1, restored.pendingCount()); // отправленная часть не в очереди
        PendingMessage m = restored.pendingMessages().get(0);
        assertTrue(m.key.startsWith("alert:"));
        assertEquals(alertRid, m.randomId); // прежний random_id
        assertEquals(1, m.attempts);
        assertTrue(m.notBeforeMs > System.currentTimeMillis()); // backoff сохранён

        // повторяется только недоставленная часть с тем же random_id
        List<Integer> sentRids = new ArrayList<>();
        restored.setCheckpoint(() -> true);
        restored.drain((peer, text, rid) -> {
            sentRids.add(rid);
            return SendResult.SENT;
        }, com.example.xdrip2vk.core.AuthContext.verified("fp-x", "user:1"),
                () -> true);
        // backoff ещё не наступил — отправок нет
        assertTrue(sentRids.isEmpty());
    }

    @Test
    public void crashAfterSendBeforeResultWriteRestoresSameRandomId() {
        VkSenderService s = startService();
        // индекс последней успешной записи ДО сетевой попытки
        final AtomicReference<String> preSendSnapshot = new AtomicReference<>();
        VkSenderService.senderOverride = (peer, text, rid) -> {
            preSendSnapshot.set(store.history.get(store.history.size() - 1));
            peers.add(peer);
            rids.add(rid);
            return SendResult.SENT; // «смерть процесса» до записи результата
        };
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        s.onDestroy();

        // модель: результат попытки потерян — последний валидный снимок до send
        DeliveryCoordinator restored = DeliveryCoordinator.load(preSendSnapshot.get());
        assertEquals(1, restored.pendingCount());
        int rid = restored.pendingMessages().get(0).randomId;
        assertEquals(rids.get(0).intValue(), rid); // повтор с прежним random_id
    }

    @Test
    public void checkpointFailureBeforeSendSkipsNetworkAndRetries() {
        VkSenderService s = startService();
        store.failWrites = true; // диск отказывает на любой записи
        // enqueue делает состояние грязным → checkpoint до send отказал
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals("send при неподтверждённом сохранении: " + peers,
                0, peers.size());
        assertEquals(1, s.pendingForTest().size());
        assertEquals(0, s.pendingForTest().get(0).attempts); // attempts не увеличен
        String text = lastNotificationText(s);
        assertTrue("ожидался статус ошибки сохранения: " + text,
                text.contains("сохранени"));
        // ограниченный повтор записи запланирован, не посекундно
        assertTrue(s.lastDrainDelayMs >= 30_000);

        // диск восстановлен — повтор записи → доставка тем же random_id
        store.failWrites = false;
        s.onStartCommand(tokenChanged(), 0, 2); // внешнее событие двигает обход
        idle(s);
        assertEquals(1, peers.size());
        assertEquals(0, s.pendingForTest().size());
        s.onDestroy();
    }

    @Test
    public void resultWriteFailureStopsNextSendThenRecovers() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();
        final boolean[] firstSend = {true};
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            if (firstSend[0]) {
                // запись результата этой попытки отказывает
                firstSend[0] = false;
                store.failNextWrite = true;
            }
            return SendResult.SENT;
        };
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);

        assertEquals("второй send после отказа записи результата: " + peers,
                1, peers.size());
        String text = lastNotificationText(s);
        assertTrue("безусловный успех при отказе записи: " + text,
                !text.contains("Успешно"));

        // хранилище восстановлено: повтор записи → остаток уходит тем же rid
        idleFor(s, java.time.Duration.ofSeconds(31));
        assertEquals(2, peers.size());
        s.onDestroy();
    }

    @Test
    public void authFailedAndOwnershipSurviveRestore() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.AUTH_FAILED;
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        int rid = rids.get(0);
        s.onDestroy();

        // восстановление: блокировка авторизации и привязки на диске
        DeliveryCoordinator restored = DeliveryCoordinator.load(store.read());
        assertTrue(restored.isAuthBlocked(
                com.example.xdrip2vk.core.Fingerprint.of("SYNTHETIC_TOKEN_1")));
        assertEquals(1, restored.pendingCount());
        PendingMessage m = restored.pendingMessages().get(0);
        assertEquals(rid, m.randomId);
        assertEquals("user:1", m.senderId);
    }

    @Test
    public void treatmentBaselinePersistedWithoutMessages() {
        prefs.edit().putBoolean("show_treatments", true).apply();
        VkSenderService s = startService();
        VkSenderService.bgDataOverride = Collections::emptyList;
        long now = System.currentTimeMillis();
        VkSenderService.treatmentsOverride = () -> Collections.singletonList(
                new TreatmentEvent("t-base", now - 60_000, 10, 0, "carbs"));
        s.onStartCommand(new Intent(context, VkSenderService.class)
                .putExtra("poll_now", true), 0, 1);
        idle(s);

        // сообщений нет (первый снимок — baseline), но baseline записан на диск
        assertEquals(0, peers.size());
        assertTrue("baseline терапии не сохранён: " + store.read(),
                store.read().contains("\"treatInit\":true")
                        && store.read().contains("t-base"));
        s.onDestroy();
    }

    // --- упорядоченное завершение ---

    @Test
    public void destroyDuringSendAppliesResultStopsRestAndSaves() {
        prefs.edit().putBoolean("alert_enabled", true).apply();
        TokenStorage.setAlertChatId(context, 888);
        VkSenderService s = startService();
        VkSenderService.senderOverride = (peer, text, rid) -> {
            peers.add(peer);
            rids.add(rid);
            s.onDestroy(); // завершение в середине первой попытки
            return SendResult.SENT;
        };
        s.onStartCommand(autoUpdate(54, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s); // завершает обход + выполняет задачу остановки

        // результат первой попытки учтён, остаток обхода не отправлен
        assertEquals(1, peers.size());
        // финальный checkpoint записан: в последнем снимке одно сообщение
        DeliveryCoordinator restored = DeliveryCoordinator.load(store.read());
        assertEquals(1, restored.pendingCount());
    }

    @Test
    public void destroyWithPendingCallbacksDoesNotResumeSends() {
        VkSenderService s = startService();
        rule = (peer) -> SendResult.RETRYABLE; // сообщение ждёт повтора
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        assertEquals(1, peers.size());
        assertTrue(s.lastDrainDelayMs > 0); // повтор был запланирован

        s.onDestroy();
        idleFor(s, java.time.Duration.ofSeconds(120));
        // поставленные callbacks не возобновили отправки после завершения
        assertEquals(1, peers.size());
    }

    @Test
    public void stateSerializationNeverOnMainThread() {
        VkSenderService s = startService();
        s.onStartCommand(autoUpdate(120, System.currentTimeMillis() - 60_000), 0, 1);
        idle(s);
        s.onDestroy();
        idle(s);

        assertFalse(store.writeThreads.isEmpty());
        for (String threadName : store.writeThreads) {
            assertTrue("запись состояния вне рабочего потока: " + threadName,
                    threadName.contains("VkSenderThread"));
        }
    }
}
