package com.example.xdrip2vk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class SettingsActivity extends AppCompatActivity {

    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        }

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null && e2.getX() - e1.getX() > 120 && Math.abs(velocityX) > 100) {
                    finishWithSlide();
                    return true;
                }
                return false;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void finishWithSlide() {
        finish();
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finishWithSlide();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        /**
         * Тестовый сигнал завершения фонового чтения файла импорта:
         * отсчитывается фоновым потоком после постановки результата в
         * главный поток — заменяет ожидание по таймеру.
         */
        static volatile java.util.concurrent.CountDownLatch importDoneForTest;

        private ActivityResultLauncher<String[]> importTokenLauncher;
        private Credentials creds;
        private final android.os.Handler mainHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            creds = Credentials.get(requireContext());
            importTokenLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::onTokenFileSelected);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            // onCreatePreferences вызывается из super.onCreate раньше
            // присваивания поля — фасад берём лениво.
            if (creds == null) {
                creds = Credentials.get(requireContext());
            }

            EditTextPreference tokenPref = findPreference("vk_token");
            if (tokenPref != null) {
                tokenPref.setPersistent(false);
                // Синхронный текст — только опубликованный снимок в памяти
                // (Keystore здесь не открывается); актуальное состояние
                // придёт с resolve ниже.
                Credentials.Snapshot initial = creds.snapshot();
                if (initial.sendPermitted()) {
                    tokenPref.setText(initial.token);
                }
                tokenPref.setSummaryProvider(preference -> {
                    Credentials.Snapshot s = creds.snapshot();
                    return tokenSummary(s);
                });
                tokenPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = newValue == null ? "" : newValue.toString().trim();
                    tokenPref.setSummaryProvider(p -> "Сохранение…");
                    // Запрос записи уже закрыл отправку старой версией;
                    // «сохранён» показывается только после подтверждения.
                    creds.setTokenOnMain(value,
                            s -> applyTokenResult(s, "Токен сохранён", null));
                    return true;
                });
            }

            Preference importToken = findPreference("import_token");
            if (importToken != null) {
                importToken.setOnPreferenceClickListener(preference -> {
                    importTokenLauncher.launch(new String[]{"text/*"});
                    return true;
                });
            }

            EditTextPreference deviceNamePref = findPreference("device_name");
            if (deviceNamePref != null) {
                deviceNamePref.setSummaryProvider(preference -> {
                    String value = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getString("device_name", "");
                    return value.isEmpty() ? "Имя для идентификации в чате (например: Мой телефон)" : value;
                });
            }

            EditTextPreference alertMinBgPref = findPreference("alert_min_bg");
            if (alertMinBgPref != null) {
                alertMinBgPref.setSummaryProvider(preference -> {
                    String value = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getString("alert_min_bg", "4.1");
                    return "Текущее значение: " + value + " ммоль/л";
                });
                alertMinBgPref.setOnPreferenceChangeListener((preference, newValue) ->
                        applyThreshold(preference, newValue, "alert_max_bg", "12.2", true));
            }

            EditTextPreference alertMaxBgPref = findPreference("alert_max_bg");
            if (alertMaxBgPref != null) {
                alertMaxBgPref.setSummaryProvider(preference -> {
                    String value = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getString("alert_max_bg", "12.2");
                    return "Текущее значение: " + value + " ммоль/л";
                });
                alertMaxBgPref.setOnPreferenceChangeListener((preference, newValue) ->
                        applyThreshold(preference, newValue, "alert_min_bg", "4.1", false));
            }

            androidx.preference.ListPreference pollIntervalPref = findPreference("poll_interval");
            if (pollIntervalPref != null) {
                pollIntervalPref.setSummaryProvider(preference -> {
                    String value = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getString("poll_interval", "300000");
                    if ("60000".equals(value)) return "1 минута";
                    if ("300000".equals(value)) return "5 минут";
                    if ("600000".equals(value)) return "10 минут";
                    return value + " мс";
                });
            }

            Preference selectChat = findPreference("select_chat");
            if (selectChat != null) {
                selectChat.setSummaryProvider(preference -> {
                    Credentials.Snapshot s = creds.snapshot();
                    if (!s.sendPermitted()) return Credentials.describe(s);
                    return s.chatId > 0 ? "Выбран чат ID: " + s.chatId : "Нажмите для выбора беседы из списка";
                });
                selectChat.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(getActivity(), VkConversationsActivity.class);
                    intent.putExtra("chat_type", "normal");
                    startActivity(intent);
                    return true;
                });
            }

            Preference selectAlertChat = findPreference("select_alert_chat");
            if (selectAlertChat != null) {
                selectAlertChat.setSummaryProvider(preference -> {
                    Credentials.Snapshot s = creds.snapshot();
                    if (!s.sendPermitted()) return Credentials.describe(s);
                    return s.alertChatId != 0 ? "Выбран чат ID: " + s.alertChatId : "Нажмите для выбора беседы для тревожных сообщений";
                });
                selectAlertChat.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(getActivity(), VkConversationsActivity.class);
                    intent.putExtra("chat_type", "alert");
                    startActivity(intent);
                    return true;
                });
            }

            androidx.preference.SwitchPreference showIobPref = findPreference("show_iob");
            if (showIobPref != null) {
                showIobPref.setSummaryProvider(preference -> {
                    boolean enabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getBoolean("show_iob", false);
                    return enabled ? "Активный инсулин будет отображаться в сообщениях" : "Активный инсулин скрыт";
                });
            }

            androidx.preference.SwitchPreference showCobPref = findPreference("show_cob");
            if (showCobPref != null) {
                showCobPref.setSummaryProvider(preference -> {
                    boolean enabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getBoolean("show_cob", false);
                    return enabled ? "Активные углеводы будут отображаться в сообщениях" : "Активные углеводы скрыты";
                });
            }

            androidx.preference.SwitchPreference showTreatmentsPref = findPreference("show_treatments");
            if (showTreatmentsPref != null) {
                showTreatmentsPref.setSummaryProvider(preference -> {
                    boolean enabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getBoolean("show_treatments", false);
                    return enabled ? "Будут отправляться разовые уведомления о вводе терапии" : "Уведомления о терапии отключены";
                });
            }

            // Подтверждённое состояние приходит с фона — экран обновляется,
            // если ещё жив; поздний callback уничтоженный экран не трогает.
            creds.resolveOnMain(s -> {
                if (!isAdded()) {
                    return;
                }
                refreshCredentialViews();
            });
        }

        /**
         * Валидация порога: конечное число в допустимом диапазоне ммоль/л,
         * нижний строго меньше верхнего. Десятичная запятая нормализуется в точку.
         */
        private boolean applyThreshold(androidx.preference.Preference preference, Object newValue,
                                       String otherKey, String otherDefault, boolean isMin) {
            try {
                double v = com.example.xdrip2vk.core.AlertThresholds
                        .parseMmolValue(String.valueOf(newValue));
                String otherRaw = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(otherKey, otherDefault);
                double other = com.example.xdrip2vk.core.AlertThresholds.parseMmolValue(otherRaw);
                boolean ok = isMin ? v < other : v > other;
                if (!ok) {
                    Toast.makeText(preference.getContext(),
                            "Нижний порог должен быть меньше верхнего", Toast.LENGTH_LONG).show();
                    return false;
                }
                // Сохраняем нормализованное значение (точка вместо запятой)
                ((EditTextPreference) preference).setText(String.valueOf(v));
                return false; // значение уже сохранено через setText
            } catch (IllegalArgumentException e) {
                Toast.makeText(preference.getContext(),
                        "Введите конечное число в ммоль/л (0.5–50)", Toast.LENGTH_LONG).show();
                return false;
            }
        }

        /**
         * Текст подсказки токена по снимку: состояние хранилища не
         * маскируется под «токен не задан».
         */
        private static String tokenSummary(Credentials.Snapshot s) {
            if (!s.sendPermitted()) {
                return Credentials.describe(s);
            }
            return s.token.isEmpty() ? "Токен не задан" : "Токен задан";
        }

        /**
         * Итог команды записи токена на экран: успех — только после
         * подтверждённого commit; при ошибке — фиксированная причина без
         * значений и текста исключения. Закрытый экран не обновляется,
         * подтверждённая операция хранения при этом не теряется.
         */
        private void applyTokenResult(Credentials.CommandResult res,
                                      String readyMessage, String deletedMessage) {
            if (!isAdded()) {
                return;
            }
            Credentials.Snapshot s = res.outcome;
            if (res.superseded) {
                // Запоздавший исход старой записи: экран обновляется по
                // актуальному снимку, старое значение не заявляется
                // текущим. Отказ самой команды показываем с её причиной.
                refreshCredentialViews();
                if (s.sendPermitted() || s.state == Credentials.State.EMPTY
                        || s.state == Credentials.State.DELETED) {
                    Toast.makeText(requireContext(),
                            Credentials.describe(creds.snapshot()),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(),
                            "Не удалось сохранить токен: "
                                    + Credentials.describe(s),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            switch (s.state) {
                case READY:
                    refreshCredentialViews();
                    VkSenderService.notifyTokenChanged(requireContext());
                    Toast.makeText(requireContext(), readyMessage, Toast.LENGTH_SHORT).show();
                    break;
                case EMPTY:
                case DELETED:
                    refreshCredentialViews();
                    VkSenderService.notifyTokenChanged(requireContext());
                    if (deletedMessage != null) {
                        Toast.makeText(requireContext(), deletedMessage, Toast.LENGTH_SHORT).show();
                    }
                    break;
                default:
                    refreshCredentialViews();
                    Toast.makeText(requireContext(),
                            "Не удалось сохранить токен: " + Credentials.describe(s),
                            Toast.LENGTH_LONG).show();
                    break;
            }
        }

        /**
         * Перечитать опубликованный снимок во всех подсказках экрана:
         * SummaryProvider при повторной установке опрашивается заново —
         * единый источник состояния.
         */
        private void refreshCredentialViews() {
            EditTextPreference tokenPref = findPreference("vk_token");
            if (tokenPref != null) {
                Credentials.Snapshot s = creds.snapshot();
                if (s.sendPermitted() || s.state == Credentials.State.EMPTY
                        || s.state == Credentials.State.DELETED) {
                    tokenPref.setText(s.token);
                }
                tokenPref.setSummaryProvider(p -> tokenSummary(creds.snapshot()));
            }
            refreshChatSummaries();
        }

        /** Обновить подсказки выбранных чатов после подтверждённого снимка. */
        private void refreshChatSummaries() {
            Preference selectChat = findPreference("select_chat");
            if (selectChat != null) {
                selectChat.setSummaryProvider(p -> {
                    Credentials.Snapshot s = creds.snapshot();
                    if (!s.sendPermitted()) return Credentials.describe(s);
                    return s.chatId > 0 ? "Выбран чат ID: " + s.chatId
                            : "Нажмите для выбора беседы из списка";
                });
            }
            Preference selectAlertChat = findPreference("select_alert_chat");
            if (selectAlertChat != null) {
                selectAlertChat.setSummaryProvider(p -> {
                    Credentials.Snapshot s = creds.snapshot();
                    if (!s.sendPermitted()) return Credentials.describe(s);
                    return s.alertChatId != 0 ? "Выбран чат ID: " + s.alertChatId
                            : "Нажмите для выбора беседы для тревожных сообщений";
                });
            }
        }

        void onTokenFileSelected(Uri uri) { // package-private — проверка пути импорта в тестах
            if (uri == null || getContext() == null) {
                return;
            }
            final Uri fileUri = uri;
            // Чтение файла — вне главного потока; запись токена — та же
            // команда фасада, что и ручной ввод.
            new Thread(() -> {
                String token = null;
                String error = null;
                try (InputStream is = requireContext().getContentResolver().openInputStream(fileUri);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    String content = sb.toString().trim();
                    if (content.isEmpty()) {
                        error = "Файл пустой";
                    } else {
                        String candidate = content.split("\\s+")[0];
                        if (candidate.isEmpty()) {
                            error = "Не удалось найти токен в файле";
                        } else {
                            token = candidate;
                        }
                    }
                } catch (Exception e) {
                    // getMessage() может содержать фрагмент содержимого файла —
                    // пользователю показывается только фиксированная причина.
                    error = "Ошибка чтения файла";
                }
                final String parsed = token;
                final String err = error;
                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (parsed == null) {
                        Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show();
                        return;
                    }
                    EditTextPreference tokenPref = findPreference("vk_token");
                    if (tokenPref != null) {
                        tokenPref.setSummaryProvider(p -> "Сохранение…");
                    }
                    creds.setTokenOnMain(parsed,
                            s -> applyTokenResult(s, "Токен импортирован", null));
                });
                java.util.concurrent.CountDownLatch l = importDoneForTest;
                if (l != null) {
                    l.countDown();
                }
            }).start();
        }
    }
}
