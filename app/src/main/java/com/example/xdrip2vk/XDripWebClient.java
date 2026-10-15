package com.example.xdrip2vk;

import android.util.Log;
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
    private static final String TREATMENTS_URL = "http://127.0.0.1:17580/api/v1/treatments.json?count=1";
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

    public static class TreatmentData {
        public double carbs;
        public double insulin;
        public long timestamp;
        public boolean hasCarbs;
        public boolean hasInsulin;

        public TreatmentData(double carbs, double insulin, long timestamp) {
            this.carbs = carbs;
            this.insulin = insulin;
            this.timestamp = timestamp;
            this.hasCarbs = carbs > 0;
            this.hasInsulin = insulin > 0;
        }
    }

    public static class BgData {
        public double bg;
        public long timestamp;
        public String direction;
        public double carbs;
        public double insulin;
        public boolean hasCarbs;
        public boolean hasInsulin;

        public BgData(double bg, long timestamp, String direction) {
            this.bg = bg;
            this.timestamp = timestamp;
            this.direction = direction;
            this.carbs = 0;
            this.insulin = 0;
            this.hasCarbs = false;
            this.hasInsulin = false;
        }
    }

    public static List<BgData> fetchBgData(String url) {
        List<BgData> result = new ArrayList<>();
        
        if (url == null || url.isEmpty()) {
            url = DEFAULT_URL;
        }

        Logger.d(TAG, "Запрос данных от xDrip+: " + url);

        HttpURLConnection connection = null;
        try {
            URL serverUrl = new URL(url);
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

                String responseData = response.toString();
        Logger.d(TAG, "Ответ от xDrip+ (сахар): " + responseData);

                result = parseSgvJson(responseData);
                Logger.d(TAG, "Распознано " + result.size() + " записей сахара");
            } else {
                Logger.e(TAG, "Ошибка HTTP от xDrip+: код " + responseCode);
            }

        } catch (Exception e) {
            Logger.e(TAG, "Ошибка получения данных от xDrip+: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return result;
    }

    private static List<BgData> parseSgvJson(String json) {
        List<BgData> result = new ArrayList<>();
        
        try {
            JSONArray jsonArray = new JSONArray(json);
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                
                double sgv = item.optDouble("sgv", 0);
                long timestamp = item.optLong("timestamp", System.currentTimeMillis());
                String direction = item.optString("direction", "");
                
                double carbs = item.optDouble("carbs", 0);
                double insulin = item.optDouble("insulin", 0);
                
                if (sgv > 0) {
                    BgData data = new BgData(sgv, timestamp, direction);
                    data.carbs = carbs;
                    data.insulin = insulin;
                    data.hasCarbs = carbs > 0;
                    data.hasInsulin = insulin > 0;
                    result.add(data);
                }
            }
            
        } catch (JSONException e) {
            Logger.e(TAG, "Ошибка разбора JSON от xDrip+: " + e.getMessage());
        }
        
        return result;
    }

    public static TreatmentData fetchTreatments() {
        Logger.d(TAG, "Запрос терапии от xDrip+: " + TREATMENTS_URL);
        
        HttpURLConnection connection = null;
        try {
            URL serverUrl = new URL(TREATMENTS_URL);
            connection = (HttpURLConnection) serverUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            Logger.d(TAG, "Код ответа терапии от xDrip+: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseData = response.toString();
                Logger.d(TAG, "Ответ терапии от xDrip+: " + responseData);

                return parseTreatmentsJson(responseData);
            } else {
                Logger.e(TAG, "Ошибка HTTP терапии от xDrip+: код " + responseCode);
            }

        } catch (Exception e) {
            Logger.e(TAG, "Ошибка получения терапии от xDrip+: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return new TreatmentData(0, 0, 0);
    }

    private static TreatmentData parseTreatmentsJson(String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);
            
            if (jsonArray.length() > 0) {
                JSONObject item = jsonArray.getJSONObject(0);
                
                double carbs = item.optDouble("carbs", 0);
                double insulin = item.optDouble("insulin", 0);
                long timestamp = item.optLong("timestamp", System.currentTimeMillis());
                
                Logger.d(TAG, "Распознана терапия: углеводы=" + carbs + ", инсулин=" + insulin);
                return new TreatmentData(carbs, insulin, timestamp);
            }
            
        } catch (JSONException e) {
            Logger.e(TAG, "Ошибка разбора JSON терапии от xDrip+: " + e.getMessage());
        }
        
        return new TreatmentData(0, 0, 0);
    }

    public static DeviceStatusData fetchDeviceStatus() {
        Logger.d(TAG, "Запрос статуса устройства от xDrip+: " + DEVICESTATUS_URL);

        HttpURLConnection connection = null;
        try {
            URL serverUrl = new URL(DEVICESTATUS_URL);
            connection = (HttpURLConnection) serverUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            Logger.d(TAG, "Код ответа статуса устройства от xDrip+: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseData = response.toString();
                Logger.d(TAG, "Ответ статуса устройства от xDrip+: " + responseData);

                return parseDeviceStatusJson(responseData);
            } else {
                Logger.e(TAG, "Ошибка HTTP статуса устройства от xDrip+: код " + responseCode);
            }

        } catch (Exception e) {
            Logger.e(TAG, "Ошибка получения статуса устройства от xDrip+: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return new DeviceStatusData(0, 0);
    }

    private static DeviceStatusData parseDeviceStatusJson(String json) {
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

                Logger.d(TAG, "Распознан статус: IoB=" + iob + ", CoB=" + cob);
                return new DeviceStatusData(iob, cob);
            }

        } catch (JSONException e) {
            Logger.e(TAG, "Ошибка разбора JSON статуса устройства от xDrip+: " + e.getMessage());
        }

        return new DeviceStatusData(0, 0);
    }
}
