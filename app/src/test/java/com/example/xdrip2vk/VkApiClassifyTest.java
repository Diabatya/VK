package com.example.xdrip2vk;

import com.example.xdrip2vk.core.SendResult;

import org.junit.Test;

import static org.junit.Assert.*;

public class VkApiClassifyTest {

    @Test
    public void successResponse() {
        assertEquals(SendResult.SENT, VkApi.classify(200, "{\"response\": 12345}"));
    }

    @Test
    public void networkFailuresAreRetryable() {
        assertEquals(SendResult.RETRYABLE, VkApi.classify(-1, null)); // таймаут/исключение
        assertEquals(SendResult.RETRYABLE, VkApi.classify(500, ""));
        assertEquals(SendResult.RETRYABLE, VkApi.classify(502, "Bad Gateway"));
        assertEquals(SendResult.RETRYABLE, VkApi.classify(200, ""));
        assertEquals(SendResult.RETRYABLE, VkApi.classify(200, "<html>not json</html>"));
    }

    @Test
    public void authFailuresDistinguished() {
        assertEquals(SendResult.AUTH_FAILED, VkApi.classify(401, ""));
        assertEquals(SendResult.AUTH_FAILED, VkApi.classify(403, ""));
        assertEquals(SendResult.AUTH_FAILED, VkApi.classify(200,
                "{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}"));
    }

    @Test
    public void transientApiErrorsRetryable() {
        assertEquals(SendResult.RETRYABLE, VkApi.classify(200,
                "{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests\"}}"));
        assertEquals(SendResult.RETRYABLE, VkApi.classify(200,
                "{\"error\":{\"error_code\":10,\"error_msg\":\"Internal server error\"}}"));
        assertEquals(SendResult.RETRYABLE, VkApi.classify(200,
                "{\"error\":{\"error_code\":29,\"error_msg\":\"Rate limit reached\"}}"));
    }

    @Test
    public void permanentApiErrorsNotRetried() {
        assertEquals(SendResult.PERMANENT, VkApi.classify(200,
                "{\"error\":{\"error_code\":100,\"error_msg\":\"One of the parameters was incorrect\"}}"));
        assertEquals(SendResult.PERMANENT, VkApi.classify(200,
                "{\"error\":{\"error_code\":7,\"error_msg\":\"Permission to perform this action is denied\"}}"));
    }
}
