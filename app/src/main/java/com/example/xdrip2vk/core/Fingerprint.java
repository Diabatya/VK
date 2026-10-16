package com.example.xdrip2vk.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Устойчивый идентификатор версии токена: SHA-256 от значения.
 * String.hashCode() для этого не подходит — у разных строк он может
 * совпадать (например "Aa" и "BB"), а сравнение токенов должно быть
 * устойчивым к коллизиям.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /** Шестнадцатеричный SHA-256 токена; "" для пустого. */
    public static String of(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
