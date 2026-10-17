package com.example.xdrip2vk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Broadcast от xDrip — НЕ источник показания: extras недоверенные и не
 * читаются вообще. Сигнал лишь инициирует ограниченный по частоте опрос
 * фиксированного локального веб-сервиса xDrip (см. VkSenderService).
 */
public class BgReceiver extends BroadcastReceiver {
    private static final String TAG = "BgReceiver";
    private static final String WAKE_LOCK_TAG = "xDrip2VK::BgReceiverWakeLock";

    /** Единственный подтверждённый action xDrip Broadcast API (BgEstimate). */
    static final String ACTION_XDRIP_BG_ESTIMATE =
            "com.eveningoutpost.dexdrip.BgEstimate";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Маршрутизация: только официальный action. Совпадение action НЕ
        // доказывает отправителя — оно лишь отсекает нерелевантные входы.
        if (intent == null || !ACTION_XDRIP_BG_ESTIMATE.equals(intent.getAction())) {
            return;
        }

        // Передача запрещена пользователем: выход до WakeLock и запуска
        // сервиса — сигнал не создаёт работу.
        if (!VkSenderService.isTransmissionAllowed(context)) {
            // Выбор ещё не подтверждён (миграция в очереди/в полёте) —
            // событие не теряем: одноразовый будильник повторной проверки.
            if (VkSenderService.isConfirmationPending()) {
                VkSenderService.scheduleConfirmationRetry(context);
            }
            Logger.d(TAG, "Передача сейчас не разрешена — broadcast проигнорирован");
            return;
        }

        // Удерживаем CPU awake на время передачи сигнала сервису (макс 10 сек)
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Logger.w(TAG, "PowerManager недоступен, пропускаем broadcast");
            return;
        }
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.acquire(10 * 1000L);

        try {
            // Extras не читаются и не переносятся: сервис получает только
            // факт сигнала и сам опрашивает веб-сервис xDrip.
            VkSenderService.notifyXdripSignal(context);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
