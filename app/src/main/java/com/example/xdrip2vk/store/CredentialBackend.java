package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;

/**
 * Подменяемый backend слоя реквизитов (задание 2Г.3Б). Реализация для
 * Android — {@link AndroidCredentialBackend}; тесты подставляют backend,
 * моделирующий диск/кеш/commit=false/исключения/прерывания.
 *
 * Контракт:
 * - snapshot возвращает источник с реальным состоянием чтения
 *   (READABLE/UNAVAILABLE/CORRUPT) и реальными типами значений;
 * - putCredentials пишет три ключа реквизитов в encrypted ОДНИМ commit;
 * - removeCredentials удаляет ТОЛЬКО три ключа реквизитов указанного
 *   источника ОДНИМ commit (никакого clear());
 * - результат boolean — факт commit()==true; «ключ пропал из кеша»
 *   успехом не считается.
 */
public interface CredentialBackend {

    /**
     * Идентичность процессного хранилища (набор файлов): backend'ы с
     * одинаковым id разделяют координатор и незавершённые операции.
     * Новый объект backend с тем же id — НЕ новый процесс и не обходит
     * защиту. Только настоящий новый процесс восстанавливается из диска.
     */
    Object storageId();

    /** Снимок одного источника для CredentialAudit. */
    Source snapshot(Role role);

    /** Записать реквизиты в encrypted одной операцией. */
    boolean putCredentials(String token, long chatId, long alertChatId);

    /** Удалить три ключа реквизитов из источника. */
    boolean removeCredentials(Role role);
}
