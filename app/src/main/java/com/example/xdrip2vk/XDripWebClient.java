package com.example.xdrip2vk;

import com.example.xdrip2vk.core.SgvReading;
import com.example.xdrip2vk.core.TreatmentEvent;
import com.example.xdrip2vk.core.XdripJson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class XDripWebClient {
    private static final String TAG = "XDripWebClient";
    private static final String DEFAULT_URL = "http://127.0.0.1:17580/sgv.json";
    private static final String TREATMENTS_URL = "http://127.0.0.1:17580/api/v1/treatments.json?count=24";
    private static final String DEVICESTATUS_URL = "http://127.0.0.1:17580/api/v1/devicestatus.json?count=1";

    public static class DeviceStatusData {
        public double iob;
        public double cob;
        public boolean hasIob;
        public boolean hasCob;

        public DeviceStatusData(double iob, double cob) {
            this.iob = iob;
            this.cob = cob;
            this.hasIob = iob > 0;
            this.hasCob = cob > 0;
        }
    }

    public static List<SgvReading> fetchBgData(String url) {
        List<SgvReading> result = new ArrayList<>();

        if (url == null || url.isEmpty()) {
            url = DEFAULT_URL;
        }

        Logger.d(TAG, "Запрос данных от xDrip+: " + url);

        String responseData = httpGet(url);
        if (responseData == null) {
            return result;
        }
        Logger.d(TAG, "Ответ от xDrip+ (сахар): " + responseData.length() + " байт");

        result = XdripJson.parseSgv(responseData);
        Logger.d(TAG, "Распознано " + result.size() + " записей сахара");
        return result;
    }

    /**
     * @return null — снимок не получен (сеть/HTTP-ошибка) или не удалось
     *         разобрать (невалидный JSON); список, в т.ч. пустой, —
     *         успешно полученный валидный ответ.
     */
    public static List<TreatmentEvent> fetchTreatments() {
        Logger.d(TAG, "Запрос терапии от xDrip+: " + TREATMENTS_URL);

        String responseData = httpGet(TREATMENTS_URL);
        if (responseData == null) {
            return null;
        }
        Logger.d(TAG, "Ответ терапии от xDrip+: " + responseData.length() + " байт");
        return XdripJson.parseTreatments(responseData);
    }

    public static DeviceStatusData fetchDeviceStatus() {
        Logger.d(TAG, "Запрос статуса устройства от xDrip+: " + DEVICESTATUS_URL);

        String responseData = httpGet(DEVICESTATUS_URL);
        if (responseData == null) {
            return new DeviceStatusData(0, 0);
        }
        Logger.d(TAG, "Ответ статуса устройства от xDrip+: " + responseData.length() + " байт");
        return parseDeviceStatusJson(responseData);
    }

    private static String httpGet(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL serverUrl = new URL(urlString);
            connection = (HttpURLConnection) serverUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            Logger.d(TAG, "Код ответа от xDrip+: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                return response.toString();
            }
            Logger.e(TAG, "Ошибка HTTP от xDrip+: код " + responseCode);
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка получения данных от xDrip+: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    static DeviceStatusData parseDeviceStatusJson(String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);

            if (jsonArray.length() > 0) {
                JSONObject item = jsonArray.getJSONObject(0);

                double iob = 0;
                double cob = 0;

                // Вариант 1: openaps.iob.iob и openaps.iob.cob (Nightscout loop)
                if (item.has("openaps")) {
                    JSONObject openaps = item.optJSONObject("openaps");
                    if (openaps != null && openaps.has("iob")) {
                        JSONObject iobObj = openaps.optJSONObject("iob");
                        if (iobObj != null) {
                            iob = iobObj.optDouble("iob", 0);
                            cob = iobObj.optDouble("cob", 0);
                        }
                    }
                }

                // Вариант 2: прямо в корне объекта
                if (iob == 0 && cob == 0) {
                    iob = item.optDouble("iob", 0);
                    cob = item.optDouble("cob", 0);
                }

                // Вариант 3: pump.iob и pump.cob
                if (iob == 0 && cob == 0 && item.has("pump")) {
                    JSONObject pump = item.optJSONObject("pump");
                    if (pump != null) {
                        iob = pump.optDouble("iob", 0);
                        cob = pump.optDouble("cob", 0);
                    }
                }

                Logger.d(TAG, "Статус устройства распознан: поля iob=" + (iob > 0)
                        + ", cob=" + (cob > 0)); // значения IoB/CoB в лог не выводятся
                return new DeviceStatusData(iob, cob);
            }

        } catch (JSONException e) {
            // e.getMessage() может содержать фрагмент ответа — в лог не выводим.
            Logger.e(TAG, "Ошибка разбора JSON статуса устройства от xDrip+: "
                    + e.getClass().getSimpleName());
        }

        return new DeviceStatusData(0, 0);
    }
}
