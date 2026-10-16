package com.example.xdrip2vk;

import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.example.xdrip2vk.core.ReadingGate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATION_PERMISSION = 100;
    private GestureDetector gestureDetector;
    private Button btnStartService;
    private Button btnStopService;
    private Chip chipStatus;
    private FloatingActionButton fabSendNow;
    private View bgCardContent;
    private TextView tvBgValue;
    private TextView tvBgDirection;
    private TextView tvBgTime;
    private TextView tvNextUpdate;
    private TextView tvVersion;
    private Handler handler;
    private Runnable updateUiRunnable;
    private long lastUpdateTime = 0;
    private long pollInterval = 300000; // 5 минут по умолчанию


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }

        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        chipStatus = findViewById(R.id.chip_status);
        fabSendNow = findViewById(R.id.fab_send_now);
        bgCardContent = findViewById(R.id.bg_card_content);
        tvBgValue = findViewById(R.id.tv_bg_value);
        tvBgDirection = findViewById(R.id.tv_bg_direction);
        tvBgTime = findViewById(R.id.tv_bg_time);
        tvNextUpdate = findViewById(R.id.tv_next_update);
        tvVersion = findViewById(R.id.tv_version);

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + versionName);
        } catch (Exception e) {
            tvVersion.setText("v1.3");
        }

        btnStartService.setOnClickListener(v -> startVkSenderService());
        btnStopService.setOnClickListener(v -> stopVkSenderService());
        fabSendNow.setOnClickListener(v -> sendNow());

        // Свайп влево → настройки
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null && e1.getX() - e2.getX() > 120 && Math.abs(velocityX) > 100) {
                    openSettings();
                    return true;
                }
                return false;
            }
        });

        handler = new Handler(Looper.getMainLooper());
        updateUiRunnable = this::updateBgDisplay;
        handler.post(updateUiRunnable);

        updateStatus();
    }

    private void updateBgDisplay() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long lastTimestamp = prefs.getLong("last_bg_timestamp", 0);
        float lastBg = prefs.getFloat("last_bg_value", 0);
        String units = prefs.getString("units", "mmol");
        String pollIntervalStr = prefs.getString("poll_interval", "300000");
        String direction = prefs.getString("last_bg_direction", "");

        if (!pollIntervalStr.isEmpty()) {
            try {
                pollInterval = Long.parseLong(pollIntervalStr);
            } catch (Exception e) {
                pollInterval = 300000;
            }
        }

        long dataAge = System.currentTimeMillis() - lastTimestamp;
        // Допустимость времени и конечность значения: будущее/невалидное
        // показание не должно выглядеть свежим на экране.
        boolean hasFreshData = lastBg > 0 && Float.isFinite(lastBg) && lastTimestamp > 0
                && ReadingGate.check(lastTimestamp, System.currentTimeMillis())
                        == ReadingGate.Verdict.FRESH;

        // Обновляем статус xDrip через Chip
        if (hasFreshData) {
            long ageMin = dataAge / 60000;
            chipStatus.setText("xDrip подключён (" + ageMin + " мин)");
            chipStatus.setChipIconResource(R.drawable.ic_status_on);
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_light);
        } else if (lastBg > 0 && lastTimestamp > 0) {
            chipStatus.setText("xDrip: нет свежих данных");
            chipStatus.setChipIconResource(R.drawable.ic_status_off);
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_orange_light);
        } else {
            chipStatus.setText("xDrip: нет данных");
            chipStatus.setChipIconResource(R.drawable.ic_status_off);
            chipStatus.setChipBackgroundColorResource(android.R.color.darker_gray);
        }

        if (hasFreshData) {
            double displayValue;
            String unitStr;

            if ("mmol".equals(units)) {
                displayValue = lastBg / 18.0;
                unitStr = String.format(Locale.getDefault(), "%.1f", displayValue);
            } else {
                displayValue = lastBg;
                unitStr = String.format(Locale.getDefault(), "%.0f", displayValue);
            }

            tvBgValue.setText(unitStr);
            tvBgDirection.setText(getDirectionArrow(direction));

            long ageMin = dataAge / 60000;
            tvBgTime.setText(ageMin + " мин назад");

            lastUpdateTime = lastTimestamp;

            // Цветовая индикация по диапазону.
            // Пороги в настройках — ммоль/л, внутреннее значение — мг/дл:
            // сравнение всегда в мг/дл независимо от единиц отображения.
            try {
                com.example.xdrip2vk.core.AlertThresholds thresholds =
                        com.example.xdrip2vk.core.AlertThresholds.parseMmol(
                                prefs.getString("alert_min_bg",
                                        com.example.xdrip2vk.core.AlertThresholds.DEFAULT_MIN_STR),
                                prefs.getString("alert_max_bg",
                                        com.example.xdrip2vk.core.AlertThresholds.DEFAULT_MAX_STR));
                if (thresholds.classify(lastBg)
                        != com.example.xdrip2vk.core.AlertThresholds.Level.NORMAL) {
                    bgCardContent.setBackgroundResource(R.drawable.bg_gradient_alert);
                } else {
                    bgCardContent.setBackgroundResource(R.drawable.bg_gradient);
                }
            } catch (Exception e) {
                bgCardContent.setBackgroundResource(R.drawable.bg_gradient);
            }
        } else {
            tvBgValue.setText("--");
            tvBgDirection.setText("");
            if (lastBg > 0 && lastTimestamp > 0) {
                tvBgTime.setText("Нет связи");
            } else {
                tvBgTime.setText("--:--");
            }
            lastUpdateTime = 0;
            bgCardContent.setBackgroundResource(R.drawable.bg_gradient);
        }

        // Обновляем отсчёт до следующего запроса
        if (lastUpdateTime > 0) {
            long elapsed = System.currentTimeMillis() - lastUpdateTime;
            long remaining = pollInterval - elapsed;
            if (remaining < 0) remaining = 0;

            long minutes = remaining / 60000;
            long seconds = (remaining % 60000) / 1000;
            tvNextUpdate.setText("Следующее обновление: " + minutes + " мин " + seconds + " сек");
        } else {
            tvNextUpdate.setText("Нет свежих данных");
        }

        handler.postDelayed(updateUiRunnable, 1000);
    }

    private String getDirectionArrow(String direction) {
        if (direction == null || direction.isEmpty()) return "";
        switch (direction) {
            case "DoubleUp": return "\u21C8";
            case "SingleUp": return "\u2191";
            case "FortyFiveUp": return "\u2197";
            case "Flat": return "\u2192";
            case "FortyFiveDown": return "\u2198";
            case "SingleDown": return "\u2193";
            case "DoubleDown": return "\u21CA";
            default: return "";
        }
    }

    private void sendNow() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long lastTimestamp = prefs.getLong("last_bg_timestamp", 0);
        float lastBg = prefs.getFloat("last_bg_value", 0);

        if (lastBg > 0 && Float.isFinite(lastBg) && lastTimestamp > 0
                && ReadingGate.check(lastTimestamp, System.currentTimeMillis())
                        == ReadingGate.Verdict.FRESH) {
            Intent intent = new Intent(this, VkSenderService.class);
            intent.putExtra("manual_send", true);
            // Контракт: bg_value — double в мг/дл, timestamp — epoch мс
            intent.putExtra("bg_value", (double) lastBg);
            intent.putExtra("timestamp", lastTimestamp);
            intent.putExtra("direction", prefs.getString("last_bg_direction", ""));
            startService(intent);
            chipStatus.setText("Отправка...");
        } else if (lastBg > 0 && lastTimestamp > 0) {
            chipStatus.setText("Данные устарели");
        } else {
            chipStatus.setText("Нет данных для отправки");
        }
    }

    private void startVkSenderService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
                return;
            }
        }

        // Android 12+: проверяем разрешение на exact alarm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this,
                        "Разрешение на точные alarm отключено. Для корректной работы в фоне откройте Настройки → Приложения → xDrip2VK → Дополнительно → Разрешения → Alarm и reminders → Разрешить",
                        Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        Intent serviceIntent = new Intent(this, VkSenderService.class);
        startForegroundService(serviceIntent);
        updateStatus();
    }

    private void stopVkSenderService() {
        Intent serviceIntent = new Intent(this, VkSenderService.class);
        stopService(serviceIntent);
        updateStatus();
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        Bundle options = ActivityOptionsCompat.makeCustomAnimation(
                this,
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
        ).toBundle();
        startActivity(intent, options);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void updateStatus() {
        if (VkSenderService.isRunning()) {
            chipStatus.setText("Сервис работает");
            chipStatus.setChipIconResource(R.drawable.ic_status_on);
        } else {
            chipStatus.setText("Сервис остановлен");
            chipStatus.setChipIconResource(R.drawable.ic_status_off);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            openSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVkSenderService();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && updateUiRunnable != null) {
            handler.removeCallbacks(updateUiRunnable);
        }
    }
}
