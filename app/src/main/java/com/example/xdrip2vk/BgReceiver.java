package com.example.xdrip2vk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import com.example.xdrip2vk.core.ReadingGate;
import com.example.xdrip2vk.core.TimeFields;

public class BgReceiver extends BroadcastReceiver {
    private static final String TAG = "BgReceiver";
    private static final String WAKE_LOCK_TAG = "xDrip2VK::BgReceiverWakeLock";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        Logger.d(TAG, "Получен broadcast от xDrip+: " + intent.getAction());

        // Передача запрещена пользователем: выходим до WakeLock, записи
        // кандидата и запуска сервиса — broadcast не ставит сообщения в очередь.
        if (!VkSenderService.isTransmissionAllowed(context)) {
            // Выбор ещё не подтверждён (миграция в очереди/в полёте) —
            // событие не теряем: одноразовый будильник повторной проверки.
            if (VkSenderService.isConfirmationPending()) {
                VkSenderService.scheduleConfirmationRetry(context);
            }
            Logger.d(TAG, "Передача сейчас не разрешена — broadcast проигнорирован");
            return;
        }

        // Удерживаем CPU awake на время обработки broadcast и старта сервиса (макс 10 сек)
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Logger.w(TAG, "PowerManager недоступен, пропускаем broadcast");
            return;
        }
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.acquire(10 * 1000L);

        try {
            // Логируем все extras из Intent
            if (intent.getExtras() != null) {
                Logger.d(TAG, "Параметры broadcast от xDrip+:");
                for (String key : intent.getExtras().keySet()) {
                    Object value = intent.getExtras().get(key);
                    Logger.d(TAG, "  Параметр " + key + " = " + value);
                }
            }

            double bgValue = extractBgValue(intent);
            Logger.d(TAG, "Извлечён сахар из broadcast: " + bgValue);

            // Отклоняем NaN, ±Infinity, ноль и отрицательные значения, а также
            // double, переполняющий float: после записи в кеш (float) он станет
            // Infinity и обойдёт проверку при восстановлении.
            if (!Double.isFinite(bgValue) || bgValue <= 0 || !Float.isFinite((float) bgValue)) {
                Logger.w(TAG, "Сахар некорректен (0, отрицательный, NaN/Infinity, переполнение float), broadcast проигнорирован");
                return;
            }

            long timestamp = extractTimestamp(intent);
            if (timestamp <= 0) {
                Logger.w(TAG, "Время в broadcast от xDrip+ отсутствует или некорректно, сообщение проигнорировано");
                return;
            }
            Logger.d(TAG, "Извлечено время из broadcast: " + timestamp);

            String direction = extractDirection(intent);

            // Валидация до любой записи: некорректное/просроченное/будущее
            // событие не попадает ни в какой кеш и не запускает сервис.
            if (ReadingGate.check(timestamp, System.currentTimeMillis()) != ReadingGate.Verdict.FRESH) {
                Logger.w(TAG, "Broadcast отклонён фильтром свежести: bg=" + bgValue + " ts=" + timestamp);
                return;
            }

            // Доверенный UI-кеш (last_bg_*) пишет только сервис для принятого
            // показания. Receiver публикует лишь кандидата — входной снимок,
            // который сервис проверит и использует для восстановления.
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("candidate_bg_timestamp", timestamp);
            editor.putFloat("candidate_bg_value", (float) bgValue);
            editor.putString("candidate_bg_direction", direction);
            editor.apply();
            Logger.d(TAG, "Данные от xDrip+ сохранены как кандидат на принятие");

            // Автоматическое обновление: НЕ считается ручной принудительной отправкой —
            // сервис применяет обычную дедупликацию по времени показания.
            Intent serviceIntent = new Intent(context, VkSenderService.class);
            serviceIntent.putExtra("auto_update", true);
            serviceIntent.putExtra("bg_value", bgValue);
            serviceIntent.putExtra("timestamp", timestamp);
            serviceIntent.putExtra("direction", direction);
            context.startForegroundService(serviceIntent);
            Logger.d(TAG, "Сервис запущен для отправки свежих данных от xDrip+ в ВК");
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private double extractBgValue(Intent intent) {
        String[] keys = {
            "com.eveningoutpost.dexdrip.Extras.BgEstimate",
            "estimate",
            "bg_value",
            "com.eveningoutpost.dexdrip.Extras.SensorBg",
            "com.eveningoutpost.dexdrip.Extras.CalculatedBg",
            "bg",
            "calculated_bg",
            "sgv",
            "com.eveningoutpost.dexdrip.Extras.BgReading",
            "glucose_value"
        };

        android.os.Bundle extras = intent.getExtras();
        if (extras == null) {
            Logger.w(TAG, "Broadcast от xDrip+ не содержит параметров (extras == null)");
            return 0;
        }

        for (String key : keys) {
            if (extras.containsKey(key)) {
                Object raw = extras.get(key);
                double value = 0;
                if (raw instanceof Number) {
                    value = ((Number) raw).doubleValue();
                } else if (raw instanceof String) {
                    try {
                        value = Double.parseDouble((String) raw);
                    } catch (NumberFormatException e) {
                        Logger.w(TAG, "Параметр сахара '" + key + "' не является числом: " + raw);
                        continue;
                    }
                } else {
                    Logger.w(TAG, "Параметр сахара '" + key + "' имеет неизвестный тип: " + (raw != null ? raw.getClass().getName() : "null"));
                    continue;
                }
                Logger.d(TAG, "Найден параметр сахара: " + key + " = " + value + " (тип: " + (raw != null ? raw.getClass().getSimpleName() : "null") + ")");
                return value;
            }
        }
        Logger.w(TAG, "Сахар не найден ни в одном известном параметре broadcast от xDrip+");
        return 0;
    }

    private long extractTimestamp(Intent intent) {
        String[] keys = {
            "com.eveningoutpost.dexdrip.Extras.Time",
            "timestamp",
            "time",
            "timestamp_seconds",
            "date"
        };

        android.os.Bundle extras = intent.getExtras();
        if (extras == null) {
            return -1;
        }
        for (String key : keys) {
            if (extras.containsKey(key)) {
                Object raw = extras.get(key);
                long value = TimeFields.toEpochMs(raw);
                if (value > 0) {
                    Logger.d(TAG, "Найден параметр времени: " + key + " = " + raw + " → " + value);
                    return value;
                }
                Logger.w(TAG, "Параметр времени '" + key + "' некорректен: " + raw);
            }
        }
        Logger.w(TAG, "Время не найдено в broadcast от xDrip+");
        return -1;
    }

    private String extractDirection(Intent intent) {
        String[] keys = {
            // Официальный ключ xDrip+ BroadcastAPI
            "com.eveningoutpost.dexdrip.Extras.BgSlopeName",
            "direction",
            "slopeName",
            "slope_name",
            "slopeArrow",
            "delta_name",
            "com.eveningoutpost.dexdrip.Extras.SlopeArrow",
            "com.eveningoutpost.dexdrip.Extras.Direction"
        };

        android.os.Bundle extras = intent.getExtras();
        if (extras == null) {
            return "";
        }
        for (String key : keys) {
            if (extras.containsKey(key)) {
                Object raw = extras.get(key);
                if (raw instanceof String && !((String) raw).isEmpty()) {
                    Logger.d(TAG, "Найден параметр тренда: " + key + " = " + raw);
                    return (String) raw;
                }
            }
        }
        return "";
    }
}
