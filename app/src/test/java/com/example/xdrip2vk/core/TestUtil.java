package com.example.xdrip2vk.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class TestUtil {
    private TestUtil() {}

    static String fixture(String name) {
        try (InputStream is = TestUtil.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
