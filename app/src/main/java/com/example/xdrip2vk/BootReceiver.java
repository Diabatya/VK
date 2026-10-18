package com.example.xdrip2vk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            // Проверяем разрешение на уведомления для Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }

            // Запускаем сервис только если пользователь разрешил передачу
            // и настроены токен и chat ID
            if (!VkSenderService.isTransmissionAllowed(context)) {
                // Boot случился до подтверждения миграции выбора — без
                // повторной проверки автозапуск был бы потерян навсегда
                // (будильники при перезагрузке стираются). При
                // подтверждённом запрете пользователя будильник не ставим.
                if (VkSenderService.isConfirmationPending()) {
                    VkSenderService.scheduleConfirmationRetry(context);
                }
                return;
            }

            // Реквизиты читаются только через фасад: на главном потоке
            // receiver доступен уже опубликованный снимок (без Keystore),
            // иначе — подтверждение состояния асинхронно, запуск только
            // после READY-снимка с токеном и чатом.
            Credentials creds = Credentials.get(context);
            if (creds.snapshot().configured()) {
                startServiceIfAllowed(context);
            } else {
                final Context app = context.getApplicationContext();
                creds.resolveOnBackground(s -> {
                    if (s.configured()) {
                        startServiceIfAllowed(app);
                    }
                });
            }
        }
    }

    /**
     * Одно правило запуска по реквизитам для синхронной ветки и
     * отложенного callback'а: действующее разрешение перепроверяется
     * непосредственно перед startForegroundService — за время чтения
     * пользователь мог остановить передачу, и запоздавший READY не
     * отменяет Stop (включая процессный запрет при отказе записи Stop).
     * Отказавший запуск не назначает новых попыток подтверждения —
     * цепочка принадлежит свежим входам, а не устаревшему callback'у.
     */
    private static void startServiceIfAllowed(Context context) {
        if (!VkSenderService.isTransmissionAllowed(context)) {
            return;
        }
        Intent serviceIntent = new Intent(context, VkSenderService.class);
        context.startForegroundService(serviceIntent);
    }
}
