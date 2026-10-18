package com.example.xdrip2vk;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.XmlResourceParser;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 2Д.1 — структура правил резервирования/переноса: все применимые
 * домены исключены во всех областях, разрешающих include нет.
 * Проверяется упакованная ресурсная структура, а не реальный backup —
 * облачное восстановление и перенос между устройствами этим не
 * моделируются.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupRulesTest {

    private static final Set<String> ALL_DOMAINS = new HashSet<>(Arrays.asList(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database",
            "device_sharedpref"));

    /** Домены, исключённые exclude-элементами внутри одного контейнера. */
    private static Set<String> parseScope(XmlResourceParser xpp,
                                          String container) throws Exception {
        Set<String> excluded = new HashSet<>();
        int depth = 0;
        boolean inside = container == null;
        while (xpp.next() != XmlPullParser.END_DOCUMENT) {
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                depth++;
                if (container != null && container.equals(xpp.getName())) {
                    inside = true;
                } else if (inside) {
                    assertEquals("разрешающий элемент недопустим: "
                            + xpp.getName(), "exclude", xpp.getName());
                    assertEquals("исключение должно покрывать домен целиком",
                            ".", xpp.getAttributeValue(null, "path"));
                    excluded.add(xpp.getAttributeValue(null, "domain"));
                }
            } else if (xpp.getEventType() == XmlPullParser.END_TAG) {
                if (container != null && inside
                        && container.equals(xpp.getName())) {
                    inside = false;
                }
                depth--;
            }
        }
        return excluded;
    }

    /** Перевести парсер на корневой элемент и вернуть его имя. */
    private static String rootName(XmlResourceParser xpp) throws Exception {
        int e;
        do {
            e = xpp.next();
        } while (e != XmlPullParser.START_TAG
                && e != XmlPullParser.END_DOCUMENT);
        assertEquals(XmlPullParser.START_TAG, e);
        return xpp.getName();
    }

    /** Проверить, что правила не содержат ни одного include. */
    private static void assertNoInclude(XmlResourceParser xpp)
            throws Exception {
        while (xpp.next() != XmlPullParser.END_DOCUMENT) {
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                assertNotEquals("разрешающий include недопустим",
                        "include", xpp.getName());
            }
        }
    }

    @Test
    public void fullBackupContentExcludesEveryDomain() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        XmlResourceParser xpp =
                context.getResources().getXml(R.xml.backup_rules);
        assertEquals("full-backup-content", rootName(xpp));
        assertEquals(ALL_DOMAINS, parseScope(xpp, null));
        xpp.close();
    }

    @Test
    public void dataExtractionRulesCoverBothScopesAllDomains()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        XmlResourceParser xpp = context.getResources()
                .getXml(R.xml.data_extraction_rules);
        assertEquals("data-extraction-rules", rootName(xpp));
        xpp.close();

        XmlResourceParser cloud = context.getResources()
                .getXml(R.xml.data_extraction_rules);
        assertEquals("data-extraction-rules", rootName(cloud));
        assertEquals(ALL_DOMAINS, parseScope(cloud, "cloud-backup"));
        cloud.close();

        XmlResourceParser d2d = context.getResources()
                .getXml(R.xml.data_extraction_rules);
        assertEquals("data-extraction-rules", rootName(d2d));
        assertEquals(ALL_DOMAINS, parseScope(d2d, "device-transfer"));
        d2d.close();
    }

    @Test
    public void rulesContainNoIncludeElements() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        XmlResourceParser a =
                context.getResources().getXml(R.xml.backup_rules);
        assertNoInclude(a);
        a.close();
        XmlResourceParser b =
                context.getResources().getXml(R.xml.data_extraction_rules);
        assertNoInclude(b);
        b.close();
    }
}
