package com.example.xdrip2vk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VkSenderService extends Service {

    private static final String TAG = "VkSenderService";
    private static final String CHANNEL_ID = "VkSenderChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_UNITS = "units";
    private static final String PREF_POLL_INTERVAL = "poll_interval";
    private static final String PREF_DEVICE_NAME = "device_name";
    private static final String PREF_ALERT_ENABLED = "alert_enabled";
    private static final String PREF_ALERT_MIN_BG = "alert_min_bg";
    private static final String PREF_ALERT_MAX_BG = "alert_max_bg";
    private static final String PREF_SHOW_IOB = "show_iob";
    private static final String PREF_SHOW_COB = "show_cob";
    private static final String PREF_SHOW_TREATMENTS = "show_treatments";
    private static volatile boolean isRunning = false;
    private HandlerThread handlerThread;
    private Handler handler;
    private SharedPreferences prefs;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    
    private double lastBg = 0;
    private long lastTimestamp = 0;
    private String lastDirection = "";
    private long lastTreatmentTimestamp = 0;
    private double lastSentBg = -1;
    private long lastSentTimestamp = 0;
    private Runnable pendingSendTask = null;
    private Runnable pollTask = null;
    private int retryCount = 0;
    private static final int[] RETRY_DELAYS = {5000, 10000, 20000, 40000};
    private static final long MAX_DATA_AGE = 900000; // 15 минут - максимальная "свежесть" данных
    private static final int ALARM_REQUEST_CODE = 1001;

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
            // Пересоздаём HandlerThread чтобы сервис продолжил работу
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
        
        // Загружаем сохранённые timestamps для защиты от дублей
        lastTreatmentTimestamp = prefs.getLong("last_treatment_timestamp", 0);
        // При первом запуске (timestamp == 0) — игнорируем все старые записи, начинаем с текущего момента
        if (lastTreatmentTimestamp == 0) {
            lastTreatmentTimestamp = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("last_treatment_timestamp", lastTreatmentTimestamp);
            editor.apply();
            Logger.d(TAG, "Первый запуск: установлена начальная метка терапии = " + lastTreatmentTimestamp);
        }
        
        // Проверяем, есть ли свежие данные от BgReceiver
        long savedTimestamp = prefs.getLong("last_bg_timestamp", 0);
        float savedBg = prefs.getFloat("last_bg_value", 0);
        Logger.d(TAG, "Загружено из настроек: сахар=" + savedBg + ", время=" + savedTimestamp + ", последняя терапия=" + lastTreatmentTimestamp);
        if (savedBg > 0 && savedTimestamp > 0) {
            long dataAge = System.currentTimeMillis() - savedTimestamp;
            if (dataAge <= MAX_DATA_AGE) {
                lastBg = savedBg;
                lastTimestamp = savedTimestamp;
                scheduleSendTask();
            } else {
                Logger.w(TAG, "Сохранённые данные устарели (возраст=" + (dataAge/60000) + " мин), пропускаем");
                updateNotification("Нет свежих данных от сенсора");
            }
        }
        
        // Планируем первый alarm
        scheduleNextAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "Команда старта сервиса");
        isRunning = true;
        
        if (intent != null) {
            boolean manualSend = intent.getBooleanExtra("manual_send", false);
            boolean pollNow = intent.getBooleanExtra("poll_now", false);
            
            if (manualSend) {
                double bgValue = intent.getDoubleExtra("bg_value", 0);
                long timestamp = intent.getLongExtra("timestamp", 0);
                
                long dataAge = System.currentTimeMillis() - timestamp;
                if (bgValue > 0 && timestamp > 0 && dataAge <= MAX_DATA_AGE) {
                    lastBg = bgValue;
                    lastTimestamp = timestamp;
                    retryCount = 0;
                    
                    // Сохраняем в SharedPreferences для отображения в UI
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putFloat("last_bg_value", (float) lastBg);
                    editor.putLong("last_bg_timestamp", lastTimestamp);
                    editor.apply();
                    
                    if (pendingSendTask != null) {
                        handler.removeCallbacks(pendingSendTask);
                    }
                    
                    lastSentBg = -1; // Сбрасываем защиту от дубликатов для ручной отправки
                    scheduleSendTask();
                } else {
                    Logger.w(TAG, "Ручная отправка отклонена: данные устарели или некорректны");
                    updateNotification("Данные устарели, отправка отменена");
                }
            } else if (pollNow) {
                // Запускаем pollXDrip сразу
                handler.post(this::pollXDrip);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        cancelAlarms();
        if (pendingSendTask != null) {
            handler.removeCallbacks(pendingSendTask);
        }
        if (pollTask != null) {
            handler.removeCallbacks(pollTask);
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

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }

    private void pollXDrip() {
        if (!isRunning) return;

        boolean acquired = false;
        try {
            // Будим процессор на время опроса — максимум 15 сек (достаточно для локальных HTTP запросов)
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(15 * 1000L);
                acquired = true;
            }

            String url = "http://127.0.0.1:17580/sgv.json";
            Logger.d(TAG, "Запрос данных от xDrip+ (веб-сервер): " + url);

            java.util.List<XDripWebClient.BgData> dataList = XDripWebClient.fetchBgData(url);

            if (!dataList.isEmpty()) {
                XDripWebClient.BgData latest = dataList.get(0);
                Logger.d(TAG, "Получены данные от xDrip+: сахар=" + latest.bg + ", время=" + latest.timestamp);

                // Проверяем, что данные не слишком старые (сенсор отвалился?)
                long dataAge = System.currentTimeMillis() - latest.timestamp;
                if (dataAge > MAX_DATA_AGE) {
                    Logger.w(TAG, "Данные слишком старые (возраст=" + (dataAge/60000) + " мин), отправка отменена");
                    // Запоминаем timestamp старых данных, чтобы не отправить их повторно при следующем poll
                    lastTimestamp = latest.timestamp;
                    lastBg = 0;
                    lastDirection = "";
                    updateNotification("Нет свежих данных от сенсора xDrip+: данные устарели более 15 минут");
                } else {
                    // Данные свежие — сохраняем для отображения в MainActivity
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putFloat("last_bg_value", (float) latest.bg);
                    editor.putLong("last_bg_timestamp", latest.timestamp);
                    editor.putString("last_bg_direction", latest.direction);
                    editor.apply();

                    // Отправляем только если timestamp новый (защита от дублей)
                    if (latest.timestamp > lastTimestamp) {
                        lastBg = latest.bg;
                        lastTimestamp = latest.timestamp;
                        lastDirection = latest.direction;
                        retryCount = 0;

                        if (pendingSendTask != null) {
                            handler.removeCallbacks(pendingSendTask);
                        }

                        scheduleSendTask();
                    } else {
                        Logger.d(TAG, "Данные не новые, пропускаем отправку");
                    }
                }
            } else {
                Logger.w(TAG, "Не получены данные от xDrip+ (веб-сервер не отвечает)");
                lastBg = 0;
                lastTimestamp = 0;
                updateNotification("Нет связи с xDrip+: проверьте, что в xDrip+ включён веб-сервер на порту 17580");
            }

            // Проверяем новые записи терапии (инсулин/углеводы) — разовые сообщения
            checkAndSendTreatments();

            // Планируем следующий alarm
            if (isRunning) {
                scheduleNextAlarm();
            }
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка при опросе xDrip+: " + e.getMessage());
        } finally {
            // Гарантированно освобождаем WakeLock
            if (acquired && wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception e) {
                    Logger.e(TAG, "Ошибка при освобождении WakeLock: " + e.getMessage());
                }
            }
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
                // Android 12+: exact alarm запрещён, используем fallback
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

    private void scheduleSendTask() {
        pendingSendTask = () -> sendToVk(false);
        handler.post(pendingSendTask);
    }

    private void checkAndSendTreatments() {
        String token = TokenStorage.getToken(this);
        long chatIdLong = TokenStorage.getChatId(this);
        String chatId = String.valueOf(chatIdLong);
        String deviceName = prefs.getString(PREF_DEVICE_NAME, "");

        if (token.isEmpty() || chatId.isEmpty() || chatId.equals("0")) {
            return;
        }

        XDripWebClient.TreatmentData treatment = XDripWebClient.fetchTreatments();
        if (treatment.timestamp <= lastTreatmentTimestamp) {
            Logger.d(TAG, "Новых записей терапии нет (последняя=" + lastTreatmentTimestamp + ", текущая=" + treatment.timestamp + ")");
            return;
        }

        boolean showTreatments = prefs.getBoolean(PREF_SHOW_TREATMENTS, false);
        if (!showTreatments) {
            Logger.d(TAG, "Уведомления о терапии отключены в настройках");
            // Сохраняем timestamp чтобы не отправить при следующем включении
            lastTreatmentTimestamp = treatment.timestamp;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("last_treatment_timestamp", lastTreatmentTimestamp);
            editor.apply();
            return;
        }

        // Новая запись терапии — отправляем разовое сообщение
        if (treatment.hasInsulin) {
            String msg = deviceName.isEmpty()
                    ? "Отмечена терапия в xDrip — инсулин: " + treatment.insulin + " ед"
                    : deviceName + ": Отмечена терапия в xDrip — инсулин: " + treatment.insulin + " ед";
            Logger.d(TAG, "Отправка разового сообщения о терапии: " + msg);
            VkApi.sendMessage(token, chatId, msg);
        }
        if (treatment.hasCarbs) {
            String msg = deviceName.isEmpty()
                    ? "Отмечена терапия в xDrip — углеводы: " + (int) treatment.carbs + "г"
                    : deviceName + ": Отмечена терапия в xDrip — углеводы: " + (int) treatment.carbs + "г";
            Logger.d(TAG, "Отправка разового сообщения о терапии: " + msg);
            VkApi.sendMessage(token, chatId, msg);
        }

        // Сохраняем timestamp последней обработанной терапии
        lastTreatmentTimestamp = treatment.timestamp;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("last_treatment_timestamp", lastTreatmentTimestamp);
        editor.apply();
    }

    private void sendToVk() {
        sendToVk(false);
    }

    private void sendToVk(boolean force) {
        Logger.d(TAG, "Отправка в ВК: сахар=" + lastBg + ", время=" + lastTimestamp + ", force=" + force);
        String token = TokenStorage.getToken(this);
        long chatIdLong = TokenStorage.getChatId(this);
        String chatId = String.valueOf(chatIdLong);
        String units = prefs.getString(PREF_UNITS, "mmol");
        String deviceName = prefs.getString(PREF_DEVICE_NAME, "");

        boolean alertEnabled = prefs.getBoolean(PREF_ALERT_ENABLED, false);
        double minBg = 4.1;
        double maxBg = 12.2;
        try {
            minBg = Double.parseDouble(prefs.getString(PREF_ALERT_MIN_BG, "4.1"));
            maxBg = Double.parseDouble(prefs.getString(PREF_ALERT_MAX_BG, "12.2"));
        } catch (NumberFormatException e) {
            Logger.e(TAG, "Некорректный диапазон тревожных сообщений в настройках: " + e.getMessage());
        }
        long alertChatIdLong = TokenStorage.getAlertChatId(this);
        String alertChatId = String.valueOf(alertChatIdLong);

        Logger.d(TAG, "Настройки: токен=" + (token.isEmpty() ? "не задан" : "задан") + ", чат=" + (chatId.isEmpty() ? "не выбран" : "выбран") + ", единицы=" + units);

        // Проверяем, что данные актуальны
        if (lastBg <= 0 || lastTimestamp <= 0) {
            Logger.w(TAG, "Нет корректных данных для отправки (сахар или время не получены)");
            updateNotification("Нет данных для отправки в ВК: сначала получите данные от сенсора");
            return;
        }

        long dataAge = System.currentTimeMillis() - lastTimestamp;
        if (dataAge > MAX_DATA_AGE) {
            Logger.w(TAG, "Данные устарели (возраст=" + (dataAge/60000) + " мин), отправка отменена");
            updateNotification("Данные устарели: сенсор не обновлялся более 15 минут, отправка отменена");
            return;
        }

        // Защита от спама: не отправляем одинаковый сахар чаще 10 минут (кроме ручной отправки)
        if (!force) {
            long timeSinceLastSend = System.currentTimeMillis() - lastSentTimestamp;
            boolean sameValue = Math.abs(lastBg - lastSentBg) < 0.01;
            if (sameValue && timeSinceLastSend < 10 * 60 * 1000) {
                Logger.w(TAG, "Значение сахара не изменилось с прошлой отправки (" + (timeSinceLastSend/60000) + " мин назад), пропускаем");
                updateNotification("Значение не изменилось, отправка пропущена");
                return;
            }
        }

        if (token.isEmpty() || chatId.isEmpty()) {
            Logger.w(TAG, "Токен ВКонтакте или ID чата не заданы");
            updateNotification("Ошибка: не задан токен ВК или не выбран чат для отправки");
            return;
        }

        double displayValue;
        String unitStr;
        
        if ("mmol".equals(units)) {
            displayValue = lastBg / 18.0;
            unitStr = String.format(Locale.getDefault(), "%.1f ммоль/л", displayValue);
        } else {
            displayValue = lastBg;
            unitStr = String.format(Locale.getDefault(), "%.0f мг/дл", displayValue);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm dd.MM", Locale.getDefault());
        String timeStr = sdf.format(new Date(lastTimestamp));
        
        // Получаем IoB и CoB из статуса устройства (расчётные остатки)
        XDripWebClient.DeviceStatusData status = XDripWebClient.fetchDeviceStatus();
        
        // Преобразуем direction в стрелку
        String arrow = getDirectionArrow(lastDirection);
        String arrowStr = arrow.isEmpty() ? "" : " " + arrow;
        
        // Формируем многострочное сообщение
        StringBuilder msgBuilder = new StringBuilder();
        if (!deviceName.isEmpty()) {
            msgBuilder.append(deviceName).append("\n");
        }
        msgBuilder.append("Глюкоза: ").append(unitStr).append(arrowStr).append(" (").append(timeStr).append(")");
        
        boolean showIob = prefs.getBoolean(PREF_SHOW_IOB, false);
        boolean showCob = prefs.getBoolean(PREF_SHOW_COB, false);

        if (showIob && status.hasIob) {
            msgBuilder.append("\nИнсулин активный: ").append(String.format(Locale.getDefault(), "%.1f", status.iob)).append(" ед");
        }
        if (showCob && status.hasCob) {
            msgBuilder.append("\nУглеводы активные: ").append((int) status.cob).append("г");
        }
        
        String message = msgBuilder.toString();
        Logger.d(TAG, "Сообщение для отправки:\n" + message);

        // Отправляем в обычный чат
        boolean success = VkApi.sendMessage(token, chatId, message);
        Logger.d(TAG, "Результат отправки в ВК: " + (success ? "успешно" : "не удалось"));

        if (success) {
            lastSentBg = lastBg;
            lastSentTimestamp = System.currentTimeMillis();
            String timeShort = timeStr.length() >= 5 ? timeStr.substring(0, 5) : timeStr;
            updateNotification("Успешно отправлено в ВК: " + unitStr + " в " + timeShort);
            retryCount = 0;
        } else {
            if (retryCount < RETRY_DELAYS.length) {
                int delay = RETRY_DELAYS[retryCount];
                updateNotification("Ошибка отправки в ВК, повтор через " + (delay/1000) + " сек");
                retryCount++;
                pendingSendTask = () -> sendToVk(false);
                handler.postDelayed(pendingSendTask, delay);
            } else {
                updateNotification("Ошибка отправки в ВК: превышен лимит попыток, проверьте токен и интернет");
            }
        }
        
        // Проверяем тревожный диапазон и отправляем в тревожный чат
        if (alertEnabled && !alertChatId.isEmpty() && !alertChatId.equals("0")) {
            double bgValue = "mmol".equals(units) ? displayValue : lastBg;
            boolean isLow = bgValue < minBg;
            boolean isHigh = bgValue > maxBg;
            
            if (isLow || isHigh) {
                String alertType = isLow ? "⚠️ ТРЕВОГА! Низкий сахар!" : "⚠️ ТРЕВОГА! Высокий сахар!";
                StringBuilder alertBuilder = new StringBuilder();
                if (!deviceName.isEmpty()) {
                    alertBuilder.append(deviceName).append("\n");
                }
                alertBuilder.append(alertType).append("\n");
                alertBuilder.append("Глюкоза: ").append(unitStr).append(arrowStr).append(" (").append(timeStr).append(")");
                
                if (showIob && status.hasIob) {
                    alertBuilder.append("\nИнсулин активный: ").append(String.format(Locale.getDefault(), "%.1f", status.iob)).append(" ед");
                }
                if (showCob && status.hasCob) {
                    alertBuilder.append("\nУглеводы активные: ").append((int) status.cob).append("г");
                }
                
                String alertMessage = alertBuilder.toString();
                Logger.d(TAG, "Тревожное сообщение:\n" + alertMessage);
                VkApi.sendMessage(token, alertChatId, alertMessage);
            }
        }
    }
}
