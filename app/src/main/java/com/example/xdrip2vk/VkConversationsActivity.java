package com.example.xdrip2vk;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VkConversationsActivity extends AppCompatActivity {
    private ListView listView;
    private ProgressBar progressBar;
    private List<Conversation> conversations;
    private String token;

    public static class Conversation {
        public String title;
        public long peerId;
        public String type;

        public Conversation(String title, long peerId, String type) {
            this.title = title;
            this.peerId = peerId;
            this.type = type;
        }

        @Override
        public String toString() {
            String typeStr = "chat".equals(type) ? "Беседа" : "Личные";
            return title + " (" + typeStr + ")";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversations);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView tvChatType = findViewById(R.id.tv_chat_type);
        String chatType = getIntent().getStringExtra("chat_type");
        if (chatType == null) chatType = "normal";

        if ("alert".equals(chatType)) {
            tvChatType.setText("Выбор тревожной беседы");
        } else {
            tvChatType.setText("Выбор обычной беседы");
        }

        listView = findViewById(R.id.list_conversations);
        progressBar = findViewById(R.id.progress_bar);
        conversations = new ArrayList<>();

        token = TokenStorage.getToken(this);

        if (token.isEmpty()) {
            Toast.makeText(this, "Сначала укажите токен VK", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);

        loadConversations();
    }

    /**
     * Тестовый сигнал завершения фоновой загрузки: выставляется тестом,
     * отсчитывается фоновым потоком ПОСЛЕ постановки результата в главный
     * поток (все пути parseResponse/failure завершаются runOnUiThread-задачей
     * или возвратом). Заменяет ожидание по таймеру; логику не меняет.
     */
    static volatile java.util.concurrent.CountDownLatch loadDoneForTest;

    private void loadConversations() {
        // Захват сигнала именно этой загрузки при её запуске: завершившийся
        // поздно поток не отсчитает latch, который выставил следующий тест.
        final java.util.concurrent.CountDownLatch l = loadDoneForTest;
        new Thread(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("count", "20");
                String response = VkApi.request("messages.getConversations", token, params);

                if (response == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Ошибка сети при загрузке бесед", Toast.LENGTH_LONG).show();
                        finishLoad();
                    });
                    return;
                }

                parseResponse(response);
            } finally {
                if (l != null) {
                    l.countDown(); // результат уже поставлен в главный поток
                }
            }
        }).start();
    }

    private void finishLoad() {
        progressBar.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
    }

    private void parseResponse(String json) {
        try {
            JSONObject responseObj = new JSONObject(json);

            if (responseObj.has("error")) {
                // error_msg/request_params могут содержать токен и данные
                // запроса — показываем фиксированное сообщение + числовой код.
                int code = VkApi.extractErrorCode(json);
                runOnUiThread(() -> {
                    Toast.makeText(this, describeApiError(code),
                            Toast.LENGTH_LONG).show();
                    finishLoad();
                });
                return;
            }

            JSONArray items = responseObj.getJSONObject("response")
                    .getJSONArray("items");

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject conversation = item.getJSONObject("conversation");
                JSONObject peer = conversation.getJSONObject("peer");

                long peerId = peer.getLong("id");
                String type = peer.getString("type");
                String title = "";

                if ("chat".equals(type)) {
                    JSONObject chatSettings = conversation.getJSONObject("chat_settings");
                    title = chatSettings.optString("title", "Беседа " + peerId);
                } else if ("user".equals(type)) {
                    title = "Личные сообщения (ID: " + peerId + ")";
                }

                conversations.add(new Conversation(title, peerId, type));
            }

            runOnUiThread(() -> {
                finishLoad();

                ArrayAdapter<Conversation> adapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_list_item_1,
                        conversations
                );
                listView.setAdapter(adapter);

                listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
                    Conversation selected = conversations.get(position);
                    String chatType = getIntent().getStringExtra("chat_type");
                    if (chatType == null) chatType = "normal";

                    if ("alert".equals(chatType)) {
                        TokenStorage.setAlertChatId(this, selected.peerId);
                    } else {
                        TokenStorage.setChatId(this, selected.peerId);
                    }

                    Toast.makeText(this, "Выбрано: " + selected.title, Toast.LENGTH_SHORT).show();
                    finish();
                });
            });

        } catch (Exception e) {
            // getMessage() может включать фрагмент ответа — только фиксированная
            // причина и класс исключения в журнал, фиксированный текст в Toast.
            Logger.e("VkConversations", "Некорректный ответ VK при загрузке бесед: "
                    + e.getClass().getSimpleName());
            runOnUiThread(() -> {
                Toast.makeText(this, "Некорректный ответ VK", Toast.LENGTH_LONG).show();
                finishLoad();
            });
        }
    }

    /**
     * Понятное сообщение по числовому коду ошибки VK без сырого текста ответа.
     * Код 5 — отказ авторизации (контракт VK API); прочие коды — общий отказ.
     */
    private static String describeApiError(int code) {
        if (code == 5) {
            return "Ошибка авторизации VK";
        }
        if (code >= 0) {
            return "Не удалось загрузить беседы (код " + code + ")";
        }
        return "Не удалось загрузить беседы";
    }
}
