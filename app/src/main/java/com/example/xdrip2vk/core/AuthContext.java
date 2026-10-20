package com.example.xdrip2vk.core;

/**
 * Контекст авторизации на момент drain/enqueue.
 *
 * tokenFp — устойчивый fingerprint версии токена (SHA-256), а не
 * String.hashCode: версии токена должны различаться без коллизий.
 *
 * senderId — подтверждённый владелец предъявленных учётных данных
 * ("user:123" / "group:456"). Личность подтверждается запросом к VK
 * именно владельца токена (users.get / groups.getById без параметров);
 * "" означает, что личность не подтверждена.
 *
 * verified=true выставляется только после успешной проверки; сетевая
 * ошибка или неопределённый ответ — это unverified, не «другой аккаунт».
 */
public final class AuthContext {

    public static final AuthContext NONE = new AuthContext("", "", false);

    public final String tokenFp;
    public final String senderId;
    public final boolean verified;

    public AuthContext(String tokenFp, String senderId, boolean verified) {
        this.tokenFp = tokenFp == null ? "" : tokenFp;
        this.senderId = senderId == null ? "" : senderId;
        this.verified = verified && !this.senderId.isEmpty();
    }

    public static AuthContext verified(String tokenFp, String senderId) {
        return new AuthContext(tokenFp, senderId, true);
    }

    public static AuthContext unverified(String tokenFp) {
        return new AuthContext(tokenFp, "", false);
    }
}
