package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;

/**
 * Подменяемый backend слоя реквизитов (задания 2Г.3Б–2Г.3В.1).
 * Реализация для Android — {@link AndroidCredentialBackend}; тесты
 * подставляют backend, моделирующий диск/кеш/commit=false/исключения.
 *
 * Контракт:
 * - snapshot возвращает источник с реальным состоянием чтения
 *   (READABLE/UNAVAILABLE/CORRUPT) и реальными типами значений;
 * - putCredentials пишет три ключа реквизитов И служебное состояние в
 *   encrypted ОДНИМ commit (serviceState — "v1:active"/"v1:deleted");
 * - deleteCredentials удаляет три ключа реквизитов и ставит состояние
 *   "v1:deleted" ОДНИМ commit того же encrypted-файла;
 * - removeCredentials удаляет ТОЛЬКО три ключа реквизитов указанного
 *   источника ОДНИМ commit (никакого clear());
 * - результат boolean — факт commit()==true; «ключ пропал из кеша»
 *   успехом не считается.
 */
public interface CredentialBackend {

    /** Формат служебного состояния: строка "v1:<state>" в enc-файле. */
    String STATE_ACTIVE = "v1:active";
    String STATE_DELETED = "v1:deleted";

    /** Служебное состояние реквизитов (хранится в encrypted-файле). */
    enum ServiceState {
        /** Ключа нет — существующая установка, обычный путь проверки. */
        ABSENT,
        /** "v1:active" — подтверждённое сохранение. */
        ACTIVE,
        /** "v1:deleted" — подтверждённое удаление пользователем. */
        DELETED,
        /** Значение есть, но неизвестно/не той версии — блокирует. */
        INVALID
    }

    /**
     * Идентичность процессного хранилища (набор файлов): backend'ы с
     * одинаковым id разделяют координатор и незавершённые операции.
     * Новый объект backend с тем же id — НЕ новый процесс и не обходит
     * защиту. Только настоящий новый процесс восстанавливается из диска.
     */
    Object storageId();

    /** Снимок одного источника для CredentialAudit. */
    Source snapshot(Role role);

    /** Служебное состояние источника (фактически только encrypted). */
    ServiceState serviceState(Role role);

    /**
     * Записать реквизиты и служебное состояние в encrypted одним
     * Editor.commit().
     */
    boolean putCredentials(String token, long chatId, long alertChatId,
                           String serviceState);

    /**
     * Подтверждённое удаление: стереть три ключа реквизитов и записать
     * состояние "v1:deleted" одним Editor.commit() того же файла.
     */
    boolean deleteCredentials();

    /** Удалить три ключа реквизитов из источника. */
    boolean removeCredentials(Role role);
}
