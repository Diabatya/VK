package com.example.xdrip2vk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    private static final String WAKE_LOCK_TAG = "xDrip2VK::AlarmWakeLock";

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.d(TAG, "Получен сигнал alarm, запускаем сервис для опроса xDrip+");

        // Передача запрещена пользователем: alarm не возобновляет работу —
        // выход до WakeLock и запуска сервиса.
        if (!VkSenderService.isTransmissionAllowed(context)) {
            Logger.d(TAG, "Передача остановлена пользователем — alarm проигнорирован");
            return;
        }

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Logger.w(TAG, "PowerManager недоступен");
            return;
        }

        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        // Держим CPU awake 15 сек — сервису нужно время на старт и взятие своего WakeLock
        wakeLock.acquire(15 * 1000L);

        try {
            Intent serviceIntent = new Intent(context, VkSenderService.class);
            serviceIntent.putExtra("poll_now", true);
            context.startForegroundService(serviceIntent);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
