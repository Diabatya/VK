package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.example.xdrip2vk.core.AuthContext;
import com.example.xdrip2vk.core.DeliveryCoordinator;
import com.example.xdrip2vk.core.SendResult;
import com.example.xdrip2vk.core.SgvReading;

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
 * Миграция 4.3 → новая схема и приём показаний через настоящий сервис.
 * Синтетические данные, отправка VK заменена на senderOverride.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VkSenderServiceMigrationTest {

    private static class Call {
        final String peer;
        final String text;
        final int rid;
        Call(String peer, String text, int rid) {
            this.peer = peer;
            this.text = text;
            this.rid = rid;
        }
    }

    private Context context;
    private SharedPreferences prefs;
    private List<Call> sends;
    private AtomicReference<SendResult> result;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CredHelper.install(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        // Тесты не про разрешение: ключ подтверждён заранее — иначе
        // чтение ставит миграционную запись в очередь и входы в окне
        // записи недетерминированно получают запрет.
        prefs.edit().putBoolean(
                VkSenderService.PREF_TRANSMISSION_ENABLED, true).commit();
        VkSenderService.resetTransmissionFlagsForTest();
        CredHelper.setToken(context, "SYNTHETIC_TOKEN");
        CredHelper.setChatId(context, 777);
        sends = new ArrayList<>();
        result = new AtomicReference<>(SendResult.SENT);
        VkSenderService.senderOverride = (peer, text, rid) -> {
            sends.add(new Call(peer, text, rid));
            return result.get();
        };
        // владелец токена подтверждён синтетически — без сети
        VkSenderService.identityVerifierOverride = (token) -> "user:1";
    }

    @After
    public void tearDown() {
        VkSenderService.senderOverride = null;
        VkSenderService.identityVerifierOverride = null;
        VkSenderService.bgDataOverride = null;
        VkSenderService.treatmentsOverride = null;
    }

    private VkSenderService startService() {
        VkSenderService s = Robolectric.buildService(VkSenderService.class).create().get();
        Shadows.shadowOf(s.workerLooperForTest()).idle();
        return s;
    }

    private void idle(VkSenderService s) {
        Shadows.shadowOf(s.workerLooperForTest()).idle();
    }

    private Intent autoUpdate(double bg, long ts, String direction) {
        Intent i = new Intent(context, VkSenderService.class);
        i.putExtra("auto_update", true);
        i.putExtra("bg_value", bg);
        i.putExtra("timestamp", ts);
        i.putExtra("direction", direction);
        return i;
    }

    // --- миграция 4.3 ---

    @Test
    public void legacyCacheFrom43IsNotAutoSent() {
        // внешне «свежий» кеш, записанный версией 4.3 — недоверен
        prefs.edit()
                .putFloat("last_bg_value", 120f)
                .putLong("last_bg_timestamp", System.currentTimeMillis() - 60_000)
                .putString("last_bg_direction", "Flat")
                .apply();
        // delivery_state_v2 отсутствует — это миграция

        VkSenderService s = startService();

        assertTrue("legacy-кеш не должен отправляться", sends.isEmpty());
        // недоверенный кеш очищен, чтобы не маскироваться под свежее
        assertEquals(0, prefs.getLong("last_bg_timestamp", -1));
        s.onDestroy();
    }

    @Test
    public void staleThenFreshSourceReadingAfterMigration() {
        VkSenderService s = startService();

        // источник возвращает старое измерение — отправки нет
        s.onStartCommand(autoUpdate(100, System.currentTimeMillis() - 20L * 60 * 1000, "Flat"), 0, 1);
        idle(s);
        assertTrue(sends.isEmpty());

        // затем приходит свежее валидное — ровно одна отправка
        s.onStartCommand(autoUpdate(100, System.currentTimeMillis() - 60_000, "Flat"), 0, 2);
        idle(s);
        assertEquals(1, sends.size());
        s.onDestroy();
    }

    @Test
    public void acceptedReadingWrittenToUiCache() {
        VkSenderService s = startService();
        long ts = System.currentTimeMillis() - 60_000;
        s.onStartCommand(autoUpdate(130, ts, "SingleDown"), 0, 1);
        idle(s);

        // UI-кеш содержит согласованный снимок только после принятия
        assertEquals(130f, prefs.getFloat("last_bg_value", 0), 0.01f);
        assertEquals(ts, prefs.getLong("last_bg_timestamp", 0));
        assertEquals("SingleDown", prefs.getString("last_bg_direction", ""));
        assertEquals(1, sends.size());
        s.onDestroy();
    }

    @Test
    public void lateOlderReadingDoesNotOverwriteUiCache() {
        VkSenderService s = startService();
        long now = System.currentTimeMillis();
        s.onStartCommand(autoUpdate(130, now - 60_000, "Flat"), 0, 1);
        idle(s);
        // запоздалое старое событие (ещё в пределах окна свежести)
        s.onStartCommand(autoUpdate(110, now - 5 * 60_000, "SingleUp"), 0, 2);
        idle(s);

        assertEquals(130f, prefs.getFloat("last_bg_value", 0), 0.01f);
        assertEquals(now - 60_000, prefs.getLong("last_bg_timestamp", 0));
        assertEquals("Flat", prefs.getString("last_bg_direction", ""));
        assertEquals(1, sends.size()); // дубликат не отправлен
        s.onDestroy();
    }

    @Test
    public void staleCandidateNotInjectedOnStart() {
        // Схема candidate_bg_* заполнялась из недоверенного broadcast —
        // путь восстановления удалён: кандидат не создаёт сообщение и
        // ключи инвалидируются при инициализации сервиса.
        prefs.edit()
                .putFloat("candidate_bg_value", 110f)
                .putLong("candidate_bg_timestamp", System.currentTimeMillis() - 60_000)
                .putString("candidate_bg_direction", "FortyFiveUp")
                .commit();

        VkSenderService s = startService();
        assertTrue("старый кандидат не отправлен", sends.isEmpty());
        assertEquals(0, prefs.getLong("candidate_bg_timestamp", 0));
        assertEquals(0f, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        s.onDestroy();
    }

    // --- обход валидации через candidate-кеш (№2А.1) ---

    @Test
    public void corruptedCandidateWithInfinityNotInjected() {
        // кеш кандидата повреждён: float Infinity — не измерение
        prefs.edit()
                .putFloat("candidate_bg_value", Float.POSITIVE_INFINITY)
                .putLong("candidate_bg_timestamp", System.currentTimeMillis() - 60_000)
                .putString("candidate_bg_direction", "Flat")
                .apply();

        VkSenderService s = startService();
        assertTrue("повреждённый кандидат не должен отправляться", sends.isEmpty());
        s.onDestroy();
    }

    @Test
    public void corruptedCandidateWithNaNOrZeroNotInjected() {
        prefs.edit()
                .putFloat("candidate_bg_value", Float.NaN)
                .putLong("candidate_bg_timestamp", System.currentTimeMillis() - 60_000)
                .apply();
        VkSenderService s = startService();
        assertTrue(sends.isEmpty());
        s.onDestroy();
    }

    @Test
    public void fullChainBroadcastHintColdStartNoDataInjection() {
        // broadcast с любым содержимым — только сигнал: extras не пишутся
        // и не переносятся; холодный запуск ничего не отправляет сам.
        Intent i = new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 1e40);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() - 60_000);
        new BgReceiver().onReceive(context, i);

        assertEquals(0, prefs.getFloat("candidate_bg_value", 0), 0.01f);
        Intent started = Shadows.shadowOf((android.app.Application) context)
                .getNextStartedService();
        assertNotNull(started);
        assertTrue(started.getBooleanExtra("xdrip_hint", false));
        assertNull(started.getExtras().get("bg_value"));

        // холодный запуск без данных веб-сервиса — отправок нет
        VkSenderService.bgDataOverride = java.util.Collections::emptyList;
        VkSenderService.treatmentsOverride = java.util.Collections::emptyList;
        VkSenderService s = startService();
        assertTrue(sends.isEmpty());
        assertEquals(0, prefs.getLong("last_bg_timestamp", 0));
        s.onDestroy();
    }

    @Test
    public void fullChainBroadcastHintColdStartWebData() {
        // Полная цепочка новой модели: broadcast → сигнал → опрос
        // веб-сервиса → отправка именно ответа сервиса.
        Intent i = new Intent(BgReceiver.ACTION_XDRIP_BG_ESTIMATE);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 999.0);
        i.putExtra("com.eveningoutpost.dexdrip.Extras.Time",
                System.currentTimeMillis() + 3600_000);
        new BgReceiver().onReceive(context, i);
        Intent started = Shadows.shadowOf((android.app.Application) context)
                .getNextStartedService();
        assertNotNull(started);

        long ts = System.currentTimeMillis() - 60_000;
        VkSenderService.bgDataOverride = () -> java.util.Collections
                .singletonList(new SgvReading(118, ts, "SingleUp", "t1"));
        VkSenderService.treatmentsOverride = java.util.Collections::emptyList;
        VkSenderService s = startService();
        s.onStartCommand(started, 0, 1);
        idle(s);

        assertEquals(1, sends.size());
        assertTrue("отправлен ответ веб-сервиса, а не extras",
                sends.get(0).text.contains("6.6"));
        assertEquals(118f, prefs.getFloat("last_bg_value", 0), 0.01f);
        assertEquals(ts, prefs.getLong("last_bg_timestamp", 0));
        s.onDestroy();
    }

    @Test
    public void pendingAndRandomIdSurviveProcessRestart() {
        VkSenderService s = startService();
        result.set(SendResult.RETRYABLE);
        s.onStartCommand(autoUpdate(100, System.currentTimeMillis() - 60_000, "Flat"), 0, 1);
        idle(s);
        assertEquals(1, sends.size());
        int rid = sends.get(0).rid;
        assertNotEquals(0, rid);
        s.onDestroy();

        // «перезапуск процесса»: состояние читается из prefs заново
        s.onDestroy();
        String state = prefs.getString("delivery_state_v2", "");
        assertFalse(state.isEmpty());
        // после notBefore, до истечения срока сообщения
        long drainNow = System.currentTimeMillis() + 5 * 60_000;
        DeliveryCoordinator restored = DeliveryCoordinator.load(state, () -> drainNow);
        assertEquals(1, restored.pendingCount());

        // повтор использует прежний random_id
        List<Call> retryCalls = new ArrayList<>();
        restored.drain((p, t, r) -> {
            retryCalls.add(new Call(p, t, r));
            return SendResult.SENT;
        }, AuthContext.verified("fp", "user:1")); // тот же отправитель
        assertEquals(1, retryCalls.size());
        assertEquals(rid, retryCalls.get(0).rid);
    }
}
