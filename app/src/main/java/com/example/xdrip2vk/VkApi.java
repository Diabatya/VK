package com.example.xdrip2vk;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class VkApi {

    private static final String TAG = "VkApi";
    private static final String API_URL = "https://api.vk.com/method/";
    private static final String API_VERSION = "5.131";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 10000;

    public static boolean sendMessage(String token, String chatId, String message) {
        if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            Logger.e(TAG, "Токен или ID чата не заданы");
            return false;
        }

        SecureRandom random = new SecureRandom();
        Map<String, String> params = new HashMap<>();
        params.put("peer_id", chatId);
        params.put("message", message);
        params.put("random_id", String.valueOf(random.nextInt()));

        String response = request("messages.send", token, params);
        boolean success = isSuccess(response);
        if (!success) {
            Logger.e(TAG, "Ошибка отправки сообщения ВК: " + response);
        }
        return success;
    }

    public static String request(String method, String token, Map<String, String> params) {
        HttpURLConnection connection = null;
        try {
            StringBuilder body = new StringBuilder();
            body.append("access_token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
                .append("&v=").append(API_VERSION);

            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    body.append("&")
                        .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name()))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8.name()));
                }
            }

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(API_URL + method);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
            }

            int responseCode = connection.getResponseCode();
            InputStream stream = (responseCode >= 200 && responseCode < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } catch (Exception e) {
            Logger.e(TAG, "Исключение при запросе к API ВКонтакте: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isSuccess(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        try {
            JSONObject json = new JSONObject(response);
            return json.has("response");
        } catch (JSONException e) {
            Logger.e(TAG, "Не удалось разобрать ответ ВК: " + e.getMessage());
            return false;
        }
    }
}
