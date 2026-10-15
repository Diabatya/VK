package com.example.xdrip2vk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;

public class BgReceiver extends BroadcastReceiver {
    private static final String TAG = "BgReceiver";
    private static final String WAKE_LOCK_TAG = "xDrip2VK::BgReceiverWakeLock";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        Logger.d(TAG, "Получен broadcast от xDrip+: " + intent.getAction());

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

            if (bgValue == 0) {
                Logger.w(TAG, "Сахар равен 0, broadcast проигнорирован");
                return;
            }

            long timestamp = extractTimestamp(intent);
            Logger.d(TAG, "Извлечено время из broadcast: " + timestamp);

            // Сохраняем данные в SharedPreferences для сервиса
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("last_bg_timestamp", timestamp);
            editor.putFloat("last_bg_value", (float) bgValue);
            editor.apply();
            Logger.d(TAG, "Данные от xDrip+ сохранены в настройки приложения");

            // Запускаем сервис с пометкой manual_send
            Intent serviceIntent = new Intent(context, VkSenderService.class);
            serviceIntent.putExtra("manual_send", true);
            serviceIntent.putExtra("bg_value", bgValue);
            serviceIntent.putExtra("timestamp", timestamp);
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
            "timestamp_seconds"
        };
        
        for (String key : keys) {
            if (intent.hasExtra(key)) {
                long value = intent.getLongExtra(key, System.currentTimeMillis());
                Logger.d(TAG, "Найден параметр времени: " + key + " = " + value);
                return value;
            }
        }
        Logger.w(TAG, "Время не найдено в broadcast от xDrip+, используем текущее время");
        return System.currentTimeMillis();
    }
}
