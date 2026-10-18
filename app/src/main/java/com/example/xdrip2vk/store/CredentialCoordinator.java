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

/**
 * Процессный координатор операций реквизитов одного процессного
 * хранилища (задание 2Г.3Б.1). Все CredentialStore'ы над backend'ами с
 * одинаковым storageId разделяют:
 * - один рабочий поток — сериализация общая, не на каждый handle;
 * - незавершённую операцию {@link PendingOp} — новый handle/backend не
 *   обходит защиту, потому что состояние живёт здесь, а не в Store.
 *
 * Закрытие handle не уничтожает состояние; worker останавливается
 * мягко (shutdown, без прерывания выполняющегося commit) и
 * пересоздаётся при новом handle. Настоящий новый процесс не видит
 * этот объект — восстановление идёт из содержимого файлов на диске.
 */
final class CredentialCoordinator {

    private static final Map<Object, CredentialCoordinator> INSTANCES =
            new HashMap<>();

    /** Незавершённая операция: исходные снимки + неподтверждённые шаги. */
    static final class PendingOp {
        /** Снимки трёх источников на момент начала операции. */
        final Source[] snapshot;
        final Plan plan;
        /** Запись назначения не подтверждена — кеш не доказательство. */
        boolean destinationUnconfirmed;
        /** Источники, очистка которых не подтверждена commit. */
        final Set<Role> pendingClears = EnumSet.noneOf(Role.class);

        PendingOp(Source[] snapshot, Plan plan) {
            this.snapshot = snapshot;
            this.plan = plan;
        }
    }

    private ExecutorService worker;
    private int handles;
    PendingOp pending; // доступ только с рабочего потока

    static synchronized CredentialCoordinator acquire(Object storageId) {
        CredentialCoordinator c = INSTANCES.get(storageId);
        if (c == null) {
            c = new CredentialCoordinator();
            INSTANCES.put(storageId, c);
        }
        c.handles++;
        return c;
    }

    synchronized <T> Future<T> submit(Callable<T> task) {
        if (worker == null || worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "credential-store"));
        }
        return worker.submit(task);
    }

    synchronized void release() {
        if (--handles <= 0) {
            handles = 0;
            if (worker != null) {
                // Мягкое закрытие: поставленные задачи завершаются,
                // выполняющийся commit не прерывается.
                worker.shutdown();
            }
        }
    }

    /** Для тестов: забыть координатор процессного хранилища. */
    static synchronized void resetForTest(Object storageId) {
        INSTANCES.remove(storageId);
    }
}
