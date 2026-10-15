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

        private ActivityResultLauncher<String[]> importTokenLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            importTokenLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::onTokenFileSelected);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            EditTextPreference tokenPref = findPreference("vk_token");
            if (tokenPref != null) {
                tokenPref.setPersistent(false);
                tokenPref.setText(TokenStorage.getToken(getContext()));
                tokenPref.setSummaryProvider(preference -> {
                    String token = TokenStorage.getToken(preference.getContext());
                    return token.isEmpty() ? "Токен не задан" : "Токен задан";
                });
                tokenPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = newValue == null ? "" : newValue.toString().trim();
                    TokenStorage.setToken(preference.getContext(), value);
                    preference.setSummary(value.isEmpty() ? "Токен не задан" : "Токен задан");
                    ((EditTextPreference) preference).setText(value);
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
            }

            EditTextPreference alertMaxBgPref = findPreference("alert_max_bg");
            if (alertMaxBgPref != null) {
                alertMaxBgPref.setSummaryProvider(preference -> {
                    String value = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext())
                            .getString("alert_max_bg", "12.2");
                    return "Текущее значение: " + value + " ммоль/л";
                });
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
                    long chatId = TokenStorage.getChatId(preference.getContext());
                    return chatId > 0 ? "Выбран чат ID: " + chatId : "Нажмите для выбора беседы из списка";
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
                    long chatId = TokenStorage.getAlertChatId(preference.getContext());
                    return chatId > 0 ? "Выбран чат ID: " + chatId : "Нажмите для выбора беседы для тревожных сообщений";
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
        }

        private void onTokenFileSelected(Uri uri) {
            if (uri == null || getContext() == null) {
                return;
            }
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                String content = sb.toString().trim();
                if (content.isEmpty()) {
                    Toast.makeText(requireContext(), "Файл пустой", Toast.LENGTH_SHORT).show();
                    return;
                }
                String token = content.split("\\s+")[0];
                if (token.isEmpty()) {
                    Toast.makeText(requireContext(), "Не удалось найти токен в файле", Toast.LENGTH_SHORT).show();
                    return;
                }
                TokenStorage.setToken(requireContext(), token);
                EditTextPreference tokenPref = findPreference("vk_token");
                if (tokenPref != null) {
                    tokenPref.setText(token);
                    tokenPref.setSummary("Токен задан");
                }
                Toast.makeText(requireContext(), "Токен импортирован", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(requireContext(), "Ошибка чтения файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
