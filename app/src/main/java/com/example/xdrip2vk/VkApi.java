package com.example.xdrip2vk;

import com.example.xdrip2vk.core.SendResult;

import org.json.JSONArray;
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
import java.util.HashMap;
import java.util.Map;

public class VkApi {

    private static final String TAG = "VkApi";
    private static final String API_URL = "https://api.vk.com/method/";
    private static final String API_VERSION = "5.131";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 10000;

    private static final class RawResponse {
        final int httpCode;
        final String body;

        RawResponse(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }

    /**
     * Отправка сообщения со стабильным random_id.
     * Один и тот же random_id при повторе не создаёт дубль на стороне VK —
     * безопасно при потере ответа после принятия сообщения сервером.
     */
    public static SendResult sendMessage(String token, String chatId, String message, int randomId) {
        if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            Logger.e(TAG, "Токен или ID чата не заданы");
            return SendResult.PERMANENT;
        }

        Map<String, String> params = new HashMap<>();
        params.put("peer_id", chatId);
        params.put("message", message);
        params.put("random_id", String.valueOf(randomId));

        RawResponse response = requestRaw("messages.send", token, params);
        SendResult result = classify(
                response == null ? -1 : response.httpCode,
                response == null ? null : response.body);
        if (result != SendResult.SENT) {
            Logger.e(TAG, "Ошибка отправки сообщения ВК (" + result + "): "
                    + (response == null ? "нет ответа" : response.body));
        }
        return result;
    }

    /** Классификация ответа: сетевая ошибка отличается от ошибки авторизации и ошибки API. */
    static SendResult classify(int httpCode, String body) {
        if (httpCode == -1) {
            return SendResult.RETRYABLE; // исключение/таймаут — временная сетевая ошибка
        }
        if (httpCode == 401 || httpCode == 403) {
            return SendResult.AUTH_FAILED;
        }
        if (httpCode >= 500 || httpCode <= 0) {
            return SendResult.RETRYABLE;
        }
        if (body == null || body.isEmpty()) {
            return SendResult.RETRYABLE;
        }
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("response")) {
                return SendResult.SENT;
            }
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                int code = error.optInt("error_code", -1);
                switch (code) {
                    case 5: // User authorization failed
                        return SendResult.AUTH_FAILED;
                    case 1:  // Unknown error — обычно временная
                    case 6:  // Too many requests per second
                    case 9:  // Flood control
                    case 10: // Internal server error
                    case 29: // Rate limit reached
                        return SendResult.RETRYABLE;
                    default:
                        return SendResult.PERMANENT;
                }
            }
            return SendResult.RETRYABLE; // неожиданное тело — лучше повторить
        } catch (JSONException e) {
            return SendResult.RETRYABLE;
        }
    }

    /**
     * Подтверждённый владелец предъявленного токена по официальному контракту
     * VK API: для пользовательского токена users.get без параметров возвращает
     * владельца токена, для токена сообщества groups.getById без параметров
     * возвращает сообщество-владельца (схема vk-api-schema, methods.json).
     * Владельца определяет только предъявленный токен — никаких user_id/group_id
     * снаружи не передаётся. Возвращает "user:&lt;id&gt;" или "group:&lt;id&gt;",
     * либо null при ошибке сети/отказе авторизации/неопределённом ответе —
     * неопределённый ответ не считается успешной проверкой.
     */
    public static String fetchTokenOwner(String token) {
        return fetchTokenOwner(token, new java.util.function.BiFunction<String, Map<String, String>, String>() {
            @Override
            public String apply(String method, Map<String, String> params) {
                return request(method, token, params);
            }
        });
    }

    /**
     * Вариант с подменяемым транспортом (тесты без сети). Метод VK и параметры
     * передаются транспорту как есть; произвольные user_id/group_id для
     * определения владельца не запрашиваются — только владелец токена.
     */
    static String fetchTokenOwner(String token,
                                  java.util.function.BiFunction<String, Map<String, String>, String> transport) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Long id = firstResponseId(transport.apply("users.get", null));
        if (id != null) {
            return "user:" + id;
        }
        id = firstResponseId(transport.apply("groups.getById", null));
        if (id != null) {
            return "group:" + id;
        }
        return null;
    }

    /**
     * Однозначный владелец из ответа VK. Принимается только массив response
     * ровно с одним объектом с положительным целочисленным id (учитываем и
     * форму {groups:[]}). Нуль, отрицательное, дробное, переполнение,
     * отсутствующий/нечисловой id, несколько объектов или ответ с ошибкой —
     * не подтверждают владельца.
     */
    private static Long firstResponseId(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("error")) {
                return null; // ответ с ошибкой — проверка не пройдена
            }
            JSONArray arr = o.optJSONArray("response");
            if (arr == null) {
                JSONObject resp = o.optJSONObject("response");
                if (resp != null) {
                    arr = resp.optJSONArray("groups");
                }
            }
            if (arr == null || arr.length() != 1) {
                return null; // пусто или неоднозначный владелец
            }
            JSONObject item = arr.optJSONObject(0);
            if (item == null) {
                return null;
            }
            Object idObj = item.opt("id");
            if (!(idObj instanceof Number)) {
                return null; // отсутствует, null или не число
            }
            double d = ((Number) idObj).doubleValue();
            long l = ((Number) idObj).longValue();
            if (d != l || l <= 0 || l > Integer.MAX_VALUE) {
                return null; // дробный, ноль, отрицательный, переполнение
            }
            return l;
        } catch (JSONException e) {
            return null; // битый JSON — неопределённый ответ
        }
    }

    public static String request(String method, String token, Map<String, String> params) {
        RawResponse r = requestRaw(method, token, params);
        return r == null ? null : r.body;
    }

    private static RawResponse requestRaw(String method, String token, Map<String, String> params) {
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

            if (stream == null) {
                return new RawResponse(responseCode, "");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return new RawResponse(responseCode, response.toString());
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
}
