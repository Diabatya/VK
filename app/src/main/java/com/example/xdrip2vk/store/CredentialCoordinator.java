package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit.Plan;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Процессный координатор операций реквизитов одного процессного
 * хранилища (задания 2Г.3Б.1/2Г.3Б.2). Все CredentialStore'ы над
 * backend'ами с одинаковым storageId разделяют worker и PendingOp.
 *
 * Инвариант передачи worker: для одного storageId никогда не
 * выполняются две операции реквизитов одновременно, включая период
 * close/open handle. Достигается тем, что worker живёт вместе с
 * координатором и НЕ останавливается при закрытии последнего handle:
 * задачи нового handle встают в ту же очередь и ждут завершения
 * выполняющегося commit — его нельзя прерывать. Daemon-поток не
 * блокирует завершение процесса. isShutdown() не используется как
 * доказательство завершения работы.
 *
 * Закрытие handle уменьшает счётчик владельцев, но состояние
 * (PendingOp, очередь) сохраняется до конца процесса. Настоящий новый
 * процесс не видит этот объект — восстановление из диска.
 */
final class CredentialCoordinator {

    private static final Map<Object, CredentialCoordinator> INSTANCES =
            new HashMap<>();

    /** Вид незавершённой операции. */
    enum OpKind {
        MIGRATION, SAVE, DELETE
    }

    /** Незавершённая операция: исходные снимки + раздельные шаги. */
    static final class PendingOp {
        final OpKind kind;
        /** Снимки трёх источников на момент начала операции. */
        final Source[] snapshot;
        final Plan plan;
        /** Запись назначения не подтверждена — кеш не доказательство. */
        boolean destinationUnconfirmed;
        /**
         * Назначение подтверждено: commit записи завершился true либо это
         * ранее существовавшая однозначная encrypted-копия. После этого
         * исходное пустое состояние назначения больше не допустимо:
         * до каждой очистки оно обязано читаться и совпадать с планом.
         */
        boolean destinationConfirmed;
        /** Очистка не подтверждена commit — обязан повторить реально. */
        final Set<Role> pendingClears = EnumSet.noneOf(Role.class);
        /** Очистка подтверждена — источник обязан оставаться пустым. */
        final Set<Role> confirmedClears = EnumSet.noneOf(Role.class);
        /**
         * Служебное состояние (cred_service_state) на момент начала
         * операции — часть ожидаемого состояния наравне с реквизитами.
         */
        final CredentialBackend.ServiceState initialState;
        /**
         * Маркер, который записывает commit этой операции одним Editor
         * с реквизитами (SAVE/MIGRATION-с-записью → ACTIVE, DELETE →
         * DELETED); null — операция маркер не меняет.
         */
        final CredentialBackend.ServiceState writtenState;
        /**
         * DELETE: commit удаления подтверждён. Повторное удаление
         * запрещено: после подтверждения выполняется только проверка
         * результата — появившиеся реквизиты не трогаем.
         */
        boolean deleteConfirmed;
        /**
         * Флаги «поле записано явным выбором» (2Г.4Б.1.1) — передаются
         * метаданными в том же commit записи и различают унаследованный
         * адресат и повторный выбор того же значения.
         */
        final boolean chatSelected;
        final boolean alertSelected;

        PendingOp(OpKind kind, Source[] snapshot, Plan plan,
                  CredentialBackend.ServiceState initialState,
                  CredentialBackend.ServiceState writtenState) {
            this(kind, snapshot, plan, initialState, writtenState,
                    false, false);
        }

        PendingOp(OpKind kind, Source[] snapshot, Plan plan,
                  CredentialBackend.ServiceState initialState,
                  CredentialBackend.ServiceState writtenState,
                  boolean chatSelected, boolean alertSelected) {
            this.kind = kind;
            this.snapshot = snapshot;
            this.plan = plan;
            this.initialState = initialState;
            this.writtenState = writtenState;
            this.chatSelected = chatSelected;
            this.alertSelected = alertSelected;
        }
    }

    private final ExecutorService worker;
    private int handles;
    PendingOp pending; // доступ только с рабочего потока

    private CredentialCoordinator() {
        // Постоянный daemon worker на время жизни хранилища в процессе:
        // передача handle не создаёт параллельного исполнителя.
        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "credential-store");
            t.setDaemon(true);
            return t;
        };
        this.worker = Executors.newSingleThreadExecutor(daemon);
    }

    static synchronized CredentialCoordinator acquire(Object storageId) {
        CredentialCoordinator c = INSTANCES.get(storageId);
        if (c == null) {
            c = new CredentialCoordinator();
            INSTANCES.put(storageId, c);
        }
        c.handles++;
        return c;
    }

    Future<CredentialStore.Resolution> submit(
            Callable<CredentialStore.Resolution> task) {
        return worker.submit(task);
    }

    synchronized void release() {
        // Идемпотентно по владельцу: убывание только от реальных handle.
        if (handles > 0) {
            handles--;
        }
        // worker сознательно не останавливается: он может выполнять
        // commit или продолжать незавершённую операцию.
    }

    /** Для тестов: забыть координатор процессного хранилища. */
    static synchronized void resetForTest(Object storageId) {
        INSTANCES.remove(storageId);
    }
}
