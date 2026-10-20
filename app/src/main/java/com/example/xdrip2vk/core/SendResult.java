package com.example.xdrip2vk.core;

/**
 * Итог одной попытки доставки сообщения.
 * RETRYABLE — временная сетевая/серверная ошибка, можно повторять.
 * AUTH_FAILED — проблема с токеном/авторизацией, повторы бессмысленны до смены токена.
 * PERMANENT — сообщение не будет доставлено никогда (ошибка API).
 */
public enum SendResult {
    SENT,
    RETRYABLE,
    AUTH_FAILED,
    PERMANENT
}
