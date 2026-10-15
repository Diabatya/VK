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

    private void loadConversations() {
        new Thread(() -> {
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
                String errorMsg = responseObj.getJSONObject("error").getString("error_msg");
                runOnUiThread(() -> {
                    Toast.makeText(this, "Ошибка VK: " + errorMsg, Toast.LENGTH_LONG).show();
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
            e.printStackTrace();
            runOnUiThread(() -> {
                Toast.makeText(this, "Ошибка парсинга: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finishLoad();
            });
        }
    }
}
