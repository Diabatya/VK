package com.example.xdrip2vk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Map;

/**
 * №2Б.2.1: строгий разбор ответа проверки владельца токена.
 * Транспорт подменён — реальной сети нет. Принимается только однозначный
 * положительный целочисленный id; user_id/group_id снаружи не запрашиваются.
 */
public class VkApiIdentityTest {

    private java.util.function.BiFunction<String, Map<String, String>, String> fixed(
            String usersGet, String groupsGetById) {
        return (method, params) -> "users.get".equals(method) ? usersGet : groupsGetById;
    }

    private String vkError() {
        return "{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}";
    }

    @Test
    public void validUserResponseReturnsUserOwner() {
        String owner = VkApi.fetchTokenOwner("t",
                fixed("{\"response\":[{\"id\":123,\"first_name\":\"Ivan\"}]}", vkError()));
        assertEquals("user:123", owner);
    }

    @Test
    public void validGroupResponseReturnsGroupOwner() {
        // массивная форма
        String owner = VkApi.fetchTokenOwner("t",
                fixed(vkError(), "{\"response\":[{\"id\":456}]}"));
        assertEquals("group:456", owner);
        // объектная форма {groups:[]}
        owner = VkApi.fetchTokenOwner("t",
                fixed(vkError(), "{\"response\":{\"groups\":[{\"id\":789}]}}"));
        assertEquals("group:789", owner);
    }

    @Test
    public void emptyBrokenAndErrorResponsesRejected() {
        assertNull(VkApi.fetchTokenOwner("t", fixed("", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{broken", "{broken")));
        assertNull(VkApi.fetchTokenOwner("t", fixed(vkError(), vkError())));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[]}", "{\"response\":[]}")));
        assertNull(VkApi.fetchTokenOwner(null, fixed("", "")));
        assertNull(VkApi.fetchTokenOwner("", fixed("", "")));
    }

    @Test
    public void zeroNegativeFractionalAndOverflowIdsRejected() {
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{\"id\":0}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{\"id\":-7}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{\"id\":1.5}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t",
                fixed("{\"response\":[{\"id\":2147483648}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{\"id\":\"42\"}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t", fixed("{\"response\":[{\"id\":null}]}", "")));
    }

    @Test
    public void ambiguousMultipleOwnersRejected() {
        assertNull(VkApi.fetchTokenOwner("t",
                fixed("{\"response\":[{\"id\":1},{\"id\":2}]}", "")));
        assertNull(VkApi.fetchTokenOwner("t",
                fixed(vkError(), "{\"response\":[{\"id\":1},{\"id\":2}]}")));
    }

    @Test
    public void networkErrorDoesNotConfirmOwner() {
        assertNull(VkApi.fetchTokenOwner("t", (method, params) -> null));
    }

    @Test
    public void noArbitraryIdsRequested() {
        java.util.List<String> methods = new java.util.ArrayList<>();
        VkApi.fetchTokenOwner("t", (method, params) -> {
            methods.add(method);
            assertNull("произвольные user_id/group_id не запрашиваются", params);
            return vkError();
        });
        assertEquals(java.util.Arrays.asList("users.get", "groups.getById"), methods);
    }
}
