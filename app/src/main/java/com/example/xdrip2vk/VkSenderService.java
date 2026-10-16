package com.example.xdrip2vk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import com.example.xdrip2vk.core.AlertThresholds;
import com.example.xdrip2vk.core.AuthContext;
import com.example.xdrip2vk.core.DeliveryCoordinator;
import com.example.xdrip2vk.core.Fingerprint;
import com.example.xdrip2vk.core.SgvReading;
import com.example.xdrip2vk.core.TreatmentEvent;
import com.example.xdrip2vk.core.Units;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VkSenderService extends Service {

    private static final String TAG = "VkSenderService";
    private static final String CHANNEL_ID = "VkSenderChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_UNITS = "units";
    private static final String PREF_DEVICE_NAME = "device_name";
    private static final String PREF_ALERT_ENABLED = "alert_enabled";
    private static final String PREF_ALERT_MIN_BG = "alert_min_bg";
    private static final String PREF_ALERT_MAX_BG = "alert_max_bg";
    private static final String PREF_SHOW_IOB = "show_iob";
    private static final String PREF_SHOW_COB = "show_cob";
    private static final String PREF_SHOW_TREATMENTS = "show_treatments";
    private static final String PREF_STATE = "delivery_state_v2";
    private static final String PREF_CONFIRMED_SENDER = "confirmed_sender";
    private static final String PREF_CONFIRMED_SENDER_FP = "confirmed_sender_fp";
    private static final String PREF_LAST_SENDER = "last_active_sender";
    /** Оба пути смены токена (ручной ввод и импорт файла) приходят сюда. */
    public static final String ACTION_TOKEN_CHANGED =
            "com.example.xdrip2vk.action.TOKEN_CHANGED";
    private static volatile boolean isRunning = false;
    private HandlerThread handlerThread;
    private Handler handler;
    private SharedPreferences prefs;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;

    private DeliveryCoordinator coordinator;
    private Runnable drainRunnable;
    private Runnable pollTask;
    private static final int ALARM_REQUEST_CODE = 1001;

    // Тестовая точка подмены отправителя для unit-тестов под Robolectric.
    static volatile DeliveryCoordinator.Sender senderOverride;

    // Тестовая точка подмены проверки владельца токена (без сети).
    static volatile java.util.function.Function<String, String> identityVerifierOverride;

    // Тестовые подмены ответов xDrip-вебсервера (без сети).
    static volatile java.util.function.Supplier<List<SgvReading>> bgDataOverride;
    static volatile java.util.function.Supplier<List<TreatmentEvent>> treatmentsOverride;

    /**
     * Единая точка оповещения о смене токена — ручной ввод и импорт файла
     * вызывают одинаковую процедуру: инвалидация прежней проверки и
     * повторная верификация новой версии на рабочем потоке сервиса.
     */
    public static void notifyTokenChanged(Context context) {
        Intent i = new Intent(context, VkSenderService.class);
        i.setAction(ACTION_TOKEN_CHANGED);
        context.startService(i);
    }

    // Последняя реально назначенная задержка drainRunnable (для тестов планировщика).
    volatile long lastDrainDelayMs = -1;

    /** Looper фонового потока — для прокачки очереди в тестах. */
    android.os.Looper workerLooperForTest() {
        return handlerThread == null ? null : handlerThread.getLooper();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.d(TAG, "Сервис создан");
        isRunning = true;
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xDrip2VK::ServiceWakeLock");

        createNotificationChannel();
        handlerThread = new HandlerThread("VkSenderThread");
        handlerThread.setUncaughtExceptionHandler((thread, ex) -> {
            Logger.e(TAG, "Критическая ошибка в фоновом потоке: " + ex.getMessage());
            try {
                handlerThread = new HandlerThread("VkSenderThread");
                handlerThread.start();
                handler = new Handler(handlerThread.getLooper());
                Logger.d(TAG, "Фоновый поток пересоздан после ошибки");
            } catch (Exception e) {
                Logger.e(TAG, "Не удалось пересоздать фоновый поток: " + e.getMessage());
            }
        });
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        startForeground(NOTIFICATION_ID, createNotification("xDrip → VK: Ожидание данных от сенсора"));

        coordinator = DeliveryCoordinator.load(prefs.getString(PREF_STATE, ""));

        drainRunnable = this::drainAndSchedule;
        pollTask = this::pollXDrip;

        // Все изменения состояния — только на фоновом потоке, строго по очереди.
        handler.post(() -> {
            if (prefs.getString(PREF_STATE, "").isEmpty()) {
                // Миграция со схемы 4.3: старый UI-кеш last_bg_* мог содержать
                // устаревшую глюкозу с ошибочно «текущим» временем. Он недоверен —
                // очищаем и не отправляем; валидные данные придут от источника.
                SharedPreferences.Editor editor = prefs.edit();
                editor.putFloat("last_bg_value", 0f);
                editor.putLong("last_bg_timestamp", 0);
                editor.putString("last_bg_direction", "");
                editor.apply();
                Logger.d(TAG, "Недоверенный кеш предыдущей версии очищен");
            }
            injectSavedReading();
            drainAndSchedule();
        });
        scheduleNextAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "Команда старта сервиса");
        isRunning = true;

        if (intent == null || handler == null) {
            return START_STICKY;
        }
        final Intent cmd = intent;
        // Сериализуем: никаких изменений состояния на главном потоке.
        handler.post(() -> {
            boolean manualSend = cmd.getBooleanExtra("manual_send", false);
            boolean autoUpdate = cmd.getBooleanExtra("auto_update", false);
            boolean pollNow = cmd.getBooleanExtra("poll_now", false);
            boolean tokenChanged = ACTION_TOKEN_CHANGED.equals(cmd.getAction());

            if (tokenChanged) {
                // Не ждём следующего показания: проверка новой версии токена
                // и возобновление допустимой очереди — сразу на рабочем потоке.
                drainAndSchedule();
            } else if (manualSend || autoUpdate) {
                SgvReading r = readingFromIntent(cmd);
                if (r == null) {
                    Logger.w(TAG, "Отклонено показание из intent: нет корректных данных");
                    if (manualSend) {
                        updateNotification("Данные устарели или некорректны, отправка отменена");
                    }
                    return;
                }
                applyReading(r, manualSend
                        ? DeliveryCoordinator.Source.MANUAL
                        : DeliveryCoordinator.Source.AUTO);
            } else if (pollNow) {
                pollXDrip();
            } else {
                drainAndSchedule();
            }
        });
        return START_STICKY;
    }

    /** Снимок показания из intent; bg_value принимается как любой Number (double/float). */
    private SgvReading readingFromIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        Object rawBg = extras.get("bg_value");
        if (!(rawBg instanceof Number)) {
            return null;
        }
        double bg = ((Number) rawBg).doubleValue();
        long ts = intent.getLongExtra("timestamp", 0);
        if (!(bg > 0) || !Double.isFinite(bg) || ts <= 0) {
            return null;
        }
        String direction = intent.getStringExtra("direction");
        return new SgvReading(bg, ts, direction == null ? "" : direction, "");
    }

    /**
     * Кандидат-показание, сохранённое BgReceiver, на случай рестарта сервиса
     * без intent. Используется только candidate_* — входной снимок, записанный
     * уже новой схемой после проверки. Устаревший last_bg_* версии 4.3 сюда
     * не попадает и автоматически не отправляется.
     */
    private void injectSavedReading() {
        float savedBg = prefs.getFloat("candidate_bg_value", 0);
        long savedTs = prefs.getLong("candidate_bg_timestamp", 0);
        // Кандидат проверяется ещё раз: повреждённый кеш (NaN/Infinity/<=0)
        // не должен доходить до coordinator и очереди.
        if (savedBg > 0 && Float.isFinite(savedBg) && savedTs > coordinator.getLastSeenBgMs()) {
            String direction = prefs.getString("candidate_bg_direction", "");
            applyReading(new SgvReading(savedBg, savedTs, direction, ""),
                    DeliveryCoordinator.Source.AUTO);
        }
    }

    private void applyReading(SgvReading r, DeliveryCoordinator.Source src) {
        long now = System.currentTimeMillis();
        DeliveryCoordinator.Config cfg = loadConfig();
        DeliveryCoordinator.Messages msgs = buildMessages(XDripWebClient.fetchDeviceStatus());

        DeliveryCoordinator.ReadingOutcome outcome = coordinator.onReading(r, src, cfg, msgs, now);
        saveState();
        Logger.d(TAG, "Показание " + r.sgvMgdl + " mg/dL @ " + r.timestampMs
                + " (" + src + ") → " + outcome);

        if (outcome == DeliveryCoordinator.ReadingOutcome.ACCEPTED) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat("last_bg_value", (float) r.sgvMgdl);
            editor.putLong("last_bg_timestamp", r.timestampMs);
            editor.putString("last_bg_direction", r.direction);
            editor.apply();
        } else if (outcome == DeliveryCoordinator.ReadingOutcome.STALE
                || outcome == DeliveryCoordinator.ReadingOutcome.FUTURE) {
            updateNotification("Нет свежих данных от сенсора xDrip+: данные устарели или из будущего");
        }
        // Пропущенная из-за невыбранного чата тревога учтена в состоянии
        // coordinator и будет отражена в статусе доставки до истечения срока.
        drainAndSchedule();
    }

    private DeliveryCoordinator.Config loadConfig() {
        String token = TokenStorage.getToken(this);
        long chatId = TokenStorage.getChatId(this);
        long alertChatId = TokenStorage.getAlertChatId(this);
        boolean alertEnabled = prefs.getBoolean(PREF_ALERT_ENABLED, false);
        AlertThresholds thresholds = AlertThresholds.orDefault(
                prefs.getString(PREF_ALERT_MIN_BG, AlertThresholds.DEFAULT_MIN_STR),
                prefs.getString(PREF_ALERT_MAX_BG, AlertThresholds.DEFAULT_MAX_STR));
        boolean configured = !token.isEmpty() && chatId > 0;
        // Невыбранный чат — только id==0. Отрицательный peer_id — допустимый
        // адресат VK (сообщество по схеме messages.send), отбрасывать его по
        // знаку нельзя; пустая строка для id<=0 создавала бы ложный статус
        // «не выбран чат» и потерю тревоги.
        // Выбранные чаты принадлежат последнему подтверждённому аккаунту —
        // нельзя считать их адресатами нового токена до проверки владельца.
        return new DeliveryCoordinator.Config(
                chatId > 0 ? String.valueOf(chatId) : "",
                alertChatId != 0 ? String.valueOf(alertChatId) : "",
                alertEnabled, configured, thresholds,
                currentAuthContext(token),
                prefs.getString(PREF_LAST_SENDER, ""));
    }

    private DeliveryCoordinator.Messages buildMessages(XDripWebClient.DeviceStatusData status) {
        String units = prefs.getString(PREF_UNITS, "mmol");
        String deviceName = prefs.getString(PREF_DEVICE_NAME, "");
        boolean showIob = prefs.getBoolean(PREF_SHOW_IOB, false);
        boolean showCob = prefs.getBoolean(PREF_SHOW_COB, false);
        return new DeliveryCoordinator.Messages() {
            @Override
            public String regular(SgvReading r) {
                return buildBgMessage(r, status, null, units, deviceName, showIob, showCob);
            }

            @Override
            public String alert(SgvReading r, AlertThresholds.Level level) {
                String type = level == AlertThresholds.Level.LOW
                        ? "⚠️ ТРЕВОГА! Низкий сахар!"
                        : "⚠️ ТРЕВОГА! Высокий сахар!";
                return buildBgMessage(r, status, type, units, deviceName, showIob, showCob);
            }

            @Override
            public String treatment(TreatmentEvent e, boolean insulinPart) {
                String part = insulinPart
                        ? "инсулин: " + e.insulin + " ед"
                        : "углеводы: " + (int) e.carbs + "г";
                return deviceName.isEmpty()
                        ? "Отмечена терапия в xDrip — " + part
                        : deviceName + ": Отмечена терапия в xDrip — " + part;
            }
        };
    }

    private String buildBgMessage(SgvReading r, XDripWebClient.DeviceStatusData status,
                                  String alertType, String units, String deviceName,
                                  boolean showIob, boolean showCob) {
        String unitStr;
        if ("mmol".equals(units)) {
            unitStr = String.format(Locale.getDefault(), "%.1f ммоль/л", Units.mgdlToMmol(r.sgvMgdl));
        } else {
            unitStr = String.format(Locale.getDefault(), "%.0f мг/дл", r.sgvMgdl);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm dd.MM", Locale.getDefault());
        String timeStr = sdf.format(new Date(r.timestampMs));
        String arrow = getDirectionArrow(r.direction);
        String arrowStr = arrow.isEmpty() ? "" : " " + arrow;

        StringBuilder sb = new StringBuilder();
        if (!deviceName.isEmpty()) {
            sb.append(deviceName).append("\n");
        }
        if (alertType != null) {
            sb.append(alertType).append("\n");
        }
        sb.append("Глюкоза: ").append(unitStr).append(arrowStr).append(" (").append(timeStr).append(")");
        if (showIob && status.hasIob) {
            sb.append("\nИнсулин активный: ").append(String.format(Locale.getDefault(), "%.1f", status.iob)).append(" ед");
        }
        if (showCob && status.hasCob) {
            sb.append("\nУглеводы активные: ").append((int) status.cob).append("г");
        }
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        saveState();
        cancelAlarms();
        if (handler != null) {
            if (drainRunnable != null) {
                handler.removeCallbacks(drainRunnable);
            }
            if (pollTask != null) {
                handler.removeCallbacks(pollTask);
            }
        }
        if (handlerThread != null) {
            handlerThread.quit();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    public static boolean isRunning() {
        return isRunning;
    }

    private void saveState() {
        if (coordinator == null) {
            return;
        }
        prefs.edit().putString(PREF_STATE, coordinator.save()).apply();
    }

    private String getDirectionArrow(String direction) {
        if (direction == null || direction.isEmpty()) return "";
        switch (direction) {
            case "DoubleUp": return "⇈";
            case "SingleUp": return "↑";
            case "FortyFiveUp": return "↗";
            case "Flat": return "→";
            case "FortyFiveDown": return "↘";
            case "SingleDown": return "↓";
            case "DoubleDown": return "⇊";
            default: return "";
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "xDrip to VK",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Уведомления о передаче данных глюкозы и терапии в ВКонтакте");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("xDrip → VK")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private volatile CharSequence lastNotificationText;

    /** Последний текст уведомления — для проверки статуса в тестах. */
    CharSequence notificationForTest() {
        return lastNotificationText;
    }

    private void updateNotification(String text) {
        lastNotificationText = text;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }

    private void pollXDrip() {
        if (!isRunning) return;

        boolean acquired = false;
        try {
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(15 * 1000L);
                acquired = true;
            }

            String url = "http://127.0.0.1:17580/sgv.json";
            Logger.d(TAG, "Запрос данных от xDrip+ (веб-сервер): " + url);

            List<SgvReading> dataList = bgDataOverride != null
                    ? bgDataOverride.get() : XDripWebClient.fetchBgData(url);
            long now = System.currentTimeMillis();

            if (!dataList.isEmpty()) {
                SgvReading latest = dataList.get(0);
                Logger.d(TAG, "Получены данные от xDrip+: сахар=" + latest.sgvMgdl + ", время=" + latest.timestampMs);
                applyReading(latest, DeliveryCoordinator.Source.AUTO);
            } else {
                Logger.w(TAG, "Не получены данные от xDrip+ (веб-сервер не отвечает)");
                updateNotification("Нет связи с xDrip+: проверьте, что в xDrip+ включён веб-сервер на порту 17580");
            }

            // Новые записи терапии (инсулин/углеводы) — все события в окне, не только одно.
            // Свежий снимок конфигурации: во время applyReading могла
            // подтвердиться смена владельца и сброс чатов — старый cfg устарел.
            DeliveryCoordinator.Config cfg = loadConfig();
            List<TreatmentEvent> treatments = treatmentsOverride != null
                    ? treatmentsOverride.get() : XDripWebClient.fetchTreatments();
            boolean showTreatments = prefs.getBoolean(PREF_SHOW_TREATMENTS, false);
            int added = coordinator.onTreatments(treatments, cfg,
                    buildMessages(new XDripWebClient.DeviceStatusData(0, 0)),
                    showTreatments, now);
            if (added > 0) {
                saveState();
            }

            if (isRunning) {
                scheduleNextAlarm();
            }
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка при опросе xDrip+: " + e.getMessage());
        } finally {
            if (acquired && wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception e) {
                    Logger.e(TAG, "Ошибка при освобождении WakeLock: " + e.getMessage());
                }
            }
        }
        drainAndSchedule();
    }

    /**
     * Auth-контекст представленного токена: fingerprint + подтверждённый
     * владелец. Подтверждённая привязка повторно используется для того же
     * fingerprint; для новой версии токена нужна новая проверка.
     */
    private AuthContext currentAuthContext(String token) {
        if (token.isEmpty()) {
            return AuthContext.NONE;
        }
        String fp = Fingerprint.of(token);
        if (fp.isEmpty()) {
            return AuthContext.NONE;
        }
        String confirmedFp = prefs.getString(PREF_CONFIRMED_SENDER_FP, "");
        if (fp.equals(confirmedFp)) {
            return AuthContext.verified(fp,
                    prefs.getString(PREF_CONFIRMED_SENDER, ""));
        }
        return AuthContext.unverified(fp);
    }

    /**
     * Проверка владельца токена запросом VK на рабочем потоке. Результат
     * применяется только если токен за время проверки не сменился: устаревший
     * ответ для прежней версии не активирует отправку. Ошибка сети или
     * неопределённый ответ — не подтверждение. null → токен сменился.
     */
    private AuthContext verifyTokenOwner(String token, String fp) {
        String owner = identityVerifierOverride != null
                ? identityVerifierOverride.apply(token)
                : VkApi.fetchTokenOwner(token);
        String tokenNow = TokenStorage.getToken(this);
        if (!Fingerprint.of(tokenNow).equals(fp)) {
            return null; // токен сменился во время проверки
        }
        if (owner == null || owner.isEmpty()) {
            return AuthContext.unverified(fp);
        }
        prefs.edit()
                .putString(PREF_CONFIRMED_SENDER, owner)
                .putString(PREF_CONFIRMED_SENDER_FP, fp)
                .apply();
        onConfirmedSender(owner);
        return AuthContext.verified(fp, owner);
    }

    /**
     * Подтверждённая смена аккаунта: чаты прежнего отправителя сбрасываются —
     * их нужно выбрать заново. Единицы, пороги и имя устройства сохраняются.
     * Старая очередь хранится в coordinator со своим владельцем до истечения.
     */
    private void onConfirmedSender(String owner) {
        String prev = prefs.getString(PREF_LAST_SENDER, "");
        if (!prev.isEmpty() && !prev.equals(owner)) {
            TokenStorage.setChatId(this, 0);
            TokenStorage.setAlertChatId(this, 0);
            Logger.w(TAG, "Подтверждён другой владелец токена — чаты нужно выбрать заново");
        }
        if (!owner.equals(prev)) {
            prefs.edit().putString(PREF_LAST_SENDER, owner).apply();
        }
    }

    private void drainAndSchedule() {
        if (coordinator == null) {
            return;
        }
        String token = TokenStorage.getToken(this);
        if (token.isEmpty()) {
            if (coordinator.pendingCount() > 0) {
                updateNotification("Ошибка: не задан токен ВК или не выбран чат для отправки");
            }
            return;
        }
        String fp = Fingerprint.of(token);
        AuthContext auth = currentAuthContext(token);
        if (!auth.verified) {
            auth = verifyTokenOwner(token, fp);
            if (auth == null) {
                // Токен заменён во время проверки — цикл с новой версией.
                drainAndSchedule();
                return;
            }
        }
        final String tokenF = token;
        DeliveryCoordinator.Sender sender = senderOverride != null
                ? senderOverride
                : (peerId, text, randomId) -> VkApi.sendMessage(tokenF, peerId, text, randomId);

        DeliveryCoordinator.Summary s = coordinator.drain(sender, auth,
                () -> fp.equals(Fingerprint.of(TokenStorage.getToken(this))));
        saveState();
        if (s.aborted) {
            // Токен заменён в середине обхода: обрабатываем новую версию —
            // проверка владельца и допустимые отправки под ней.
            Logger.d(TAG, "Токен сменился во время обхода — повторная проверка");
            drainAndSchedule();
            return;
        }
        Logger.d(TAG, "Доставка: отправлено=" + s.sent + ", повторы=" + s.retried
                + ", auth=" + s.authFailures + ", permanent=" + s.permanent + ", expired=" + s.expired);

        updateDeliveryStatus(s, auth);

        long due = coordinator.nextDueAt(auth);
        if (due > 0) {
            long delay = Math.max(due - System.currentTimeMillis(), 1000);
            lastDrainDelayMs = delay; // для тестов планировщика
            handler.removeCallbacks(drainRunnable);
            handler.postDelayed(drainRunnable, delay);
        } else {
            lastDrainDelayMs = -1;
        }
    }

    /**
     * Статус уведомления по фактическому состоянию очереди, а не только по
     * последнему Summary: успех одного сообщения не должен скрывать
     * сохраняющуюся ошибку другого, а для terminal/permanent нельзя
     * обещать повтор.
     */
    private void updateDeliveryStatus(DeliveryCoordinator.Summary s, AuthContext auth) {
        long now = System.currentTimeMillis();
        if (coordinator.isAuthBlocked(auth.tokenFp)) {
            updateNotification("Ошибка авторизации VK — проверьте токен в настройках");
            return;
        }
        if (!auth.verified) {
            updateNotification("Не удалось подтвердить владельца токена — отправка заблокирована");
            return;
        }
        int pending = coordinator.pendingCount();
        int terminal = coordinator.terminalCount();
        // Сообщения другого подтверждённого аккаунта или старой схемы без
        // владельца — заблокированы до истечения срока, а не «ждут повтора».
        int blocked = coordinator.blockedCount(auth);
        int eligible = pending - blocked;
        String blockedNote = blocked > 0
                ? "очередь прежнего аккаунта или версии заблокирована — выберите чаты заново"
                : null;
        // Пропущенная из-за невыбранного чата тревога — сохраняющееся состояние,
        // а не одноразовый текст: её не должен скрывать поздний успех других
        // сообщений. После истечения срока явно сообщаем, что она не отправлена.
        long missedUntil = coordinator.missedAlertUntil();
        String missed = missedUntil > 0
                ? (now < missedUntil
                        ? "Тревога не отправлена: для тревог не выбран чат — укажите его в настройках"
                        : "Срок тревоги истёк — она не была отправлена (чат тревог не был выбран)")
                : null;
        if (eligible > 0) {
            long due = coordinator.nextDueAt(auth);
            if (due > 0) {
                StringBuilder b = new StringBuilder(
                        "Часть сообщений не доставлена, повтор через ")
                        .append(Math.max(due - now, 0) / 1000).append(" сек");
                if (terminal > 0) {
                    b.append(" (есть окончательные ошибки)");
                }
                if (blockedNote != null) {
                    b.append(" · ").append(blockedNote);
                }
                if (missed != null) {
                    b.append(" · ").append(missed);
                }
                updateNotification(b.toString());
                return;
            }
        }
        if (terminal > 0) {
            updateNotification("Часть сообщений не доставлена — окончательная ошибка, повтор не планируется"
                    + (blockedNote != null ? " · " + blockedNote : "")
                    + (missed != null ? " · " + missed : ""));
            return;
        }
        if (blockedNote != null) {
            updateNotification("Часть сообщений не доставлена — " + blockedNote
                    + (missed != null ? " · " + missed : ""));
            return;
        }
        if (missed != null) {
            updateNotification(missed);
            return;
        }
        if (s.expired > 0) {
            updateNotification("Срок части сообщений истёк — данные устарели и не были отправлены");
            return;
        }
        if (s.sent > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            updateNotification("Успешно отправлено в ВК в " + sdf.format(new Date(now)));
        }
    }

    private void scheduleNextAlarm() {
        long interval = 300000; // 5 минут по умолчанию
        try {
            interval = Long.parseLong(prefs.getString("poll_interval", "300000"));
        } catch (Exception e) {
            Logger.e(TAG, "Некорректный интервал опроса в настройках, используется 5 минут по умолчанию");
        }

        long triggerTime = System.currentTimeMillis() + interval;

        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (alarmManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
                Logger.d(TAG, "Следующий точный запрос запланирован через " + (interval/1000) + " сек");
            } catch (SecurityException e) {
                Logger.w(TAG, "Exact alarm запрещён системой, используем fallback (не exact alarm)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
                Logger.d(TAG, "Следующий запрос (fallback) запланирован через " + (interval/1000) + " сек");
            }
        }
    }

    private void cancelAlarms() {
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            Logger.d(TAG, "Запросы остановлены");
        }
    }
}
