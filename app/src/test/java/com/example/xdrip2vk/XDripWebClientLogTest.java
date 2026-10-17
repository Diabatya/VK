package com.example.xdrip2vk;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Журнал настоящего парсера XDripWebClient (без bgDataOverride — исполняется
 * реальный код разбора): медицинские значения и сырой payload в лог не
 * попадают; допустимы факт разбора, наличие полей, код/класс ошибки и размер.
 * Перехват — ShadowLog (Logger обёрнут над android.util.Log).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class XDripWebClientLogTest {

    @Before
    public void clearLogs() {
        ShadowLog.clear();
    }

    private List<String> allMessages() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            out.add(item.msg);
        }
        return out;
    }

    @Test
    public void deviceStatusParseDoesNotLogMedicalValues() {
        // Уникальные синтетические значения — контрольные для поиска в логе.
        String json = "[{\"openaps\":{\"iob\":{\"iob\":4.321,\"cob\":27.99}}}]";

        XDripWebClient.DeviceStatusData d =
                XDripWebClient.parseDeviceStatusJson(json);

        // Данные разбираются правильно.
        assertNotNull(d);
        assertTrue(d.hasIob);
        assertTrue(d.hasCob);
        assertEquals(4.321, d.iob, 0.001);
        assertEquals(27.99, d.cob, 0.001);

        // Ни одно сообщение журнала не содержит значений и полного ответа.
        for (String m : allMessages()) {
            assertFalse("IoB в лог не попадает: " + m, m.contains("4.321"));
            assertFalse("CoB в лог не попадает: " + m, m.contains("27.99"));
            assertFalse("полный payload в лог не попадает: " + m,
                    m.contains(json));
        }
        // Факт успешного разбора журналируется.
        boolean parsedLogged = false;
        for (String m : allMessages()) {
            if (m.contains("Статус устройства распознан")) {
                parsedLogged = true;
            }
        }
        assertTrue("факт разбора зафиксирован", parsedLogged);
    }

    @Test
    public void invalidStatusDoesNotLeakPayloadToLog() {
        // Уникальная контрольная строка внутри битого ответа: JSONException
        // org.json включает фрагмент входа в message — его в лог быть не должно.
        String marker = "ZZZ_CONTROL_9f3d7a";
        String json = "[{\"openaps\":{\"iob\":\"" + marker + "\"}";

        XDripWebClient.DeviceStatusData d =
                XDripWebClient.parseDeviceStatusJson(json);

        // Битый ответ → пустой статус (не null, значений нет).
        assertNotNull(d);
        assertFalse(d.hasIob);
        assertFalse(d.hasCob);
        assertEquals(0, d.iob, 0.001);

        for (String m : allMessages()) {
            assertFalse("контрольная строка payload в лог не попадает: " + m,
                    m.contains(marker));
            assertFalse("фрагмент ответа в лог не попадает: " + m,
                    m.contains(json));
        }
    }
}
