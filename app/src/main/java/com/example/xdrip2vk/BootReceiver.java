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

            // Запускаем сервис только если настроены токен и chat ID
            String token = TokenStorage.getToken(context);
            long chatId = TokenStorage.getChatId(context);

            if (!token.isEmpty() && chatId > 0) {
                Intent serviceIntent = new Intent(context, VkSenderService.class);
                context.startForegroundService(serviceIntent);
            }
        }
    }
}
