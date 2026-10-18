package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit;
import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Outcome;
import com.example.xdrip2vk.core.CredentialAudit.Plan;
import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.CredentialAudit.Verdict;
import com.example.xdrip2vk.store.CredentialCoordinator.PendingOp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Исполнитель слоя реквизитов (задания 2Г.3Б/2Г.3Б.1): читает снимки
 * через {@link CredentialBackend}, решает настоящим
 * {@link CredentialAudit} и выполняет только однозначный план.
 *
 * Не подключён к действующим TokenStorage/Activity/receivers/сервису.
 *
 * Владение состоянием: сериализация и незавершённые операции живут в
 * {@link CredentialCoordinator} по storageId хранилища — общие для всех
 * handle/backend в процессе. Замена Store или backend не является
 * новым процессом и не делает кеш достоверным: неподтверждённые записи
 * и очистки учитываются всеми экземплярами. Только настоящий новый
 * процесс восстанавливается из реально сохранённых данных на диске.
 *
 * commit()==true — единственное подтверждение записи/очистки:
 * read-back из того же SharedPreferences не доверяется. READY/EMPTY из
 * аудита грязного кеша не снимают незавершённую операцию.
 */
public final class CredentialStore implements AutoCloseable {

    /** Фаза незавершённой операции в процессе. */
    public enum Pending {
        NONE,
        /** Запись назначения не подтверждена commit. */
        DESTINATION_WRITE,
        /** Очистка одного или нескольких источников не подтверждена. */
        SOURCE_CLEAR,
        /** Реквизиты изменились во время операции — нужна новая проверка. */
        SOURCE_CHANGED
    }

    /** Фиксированные коды исходов — без значений. */
    public enum Status {
        /** Однозначные защищённые реквизиты — можно использовать. */
        READY,
        /** Реквизитов нет. */
        EMPTY,
        /** Блокирующий CONFLICT/UNAVAILABLE/CORRUPT аудита. */
        BLOCKED,
        /** Операция не завершена: запись/очистка/перепроверка. */
        INCOMPLETE
    }

    /** Итог resolve(): статус + причина аудита + реквизиты (только READY). */
    public static final class Resolution {
        public final Status status;
        public final Reason reason;
        public final Pending pending;
        public final String token;
        public final long chatId;
        public final long alertChatId;

        private Resolution(Status status, Reason reason, Pending pending,
                           String token, long chatId, long alertChatId) {
            this.status = status;
            this.reason = reason;
            this.pending = pending;
            this.token = token;
            this.chatId = chatId;
            this.alertChatId = alertChatId;
        }

        /** Право использовать токен для отправки — только READY. */
        public boolean sendPermitted() {
            return status == Status.READY;
        }

        @Override
        public String toString() {
            return "Resolution{" + status + "," + reason + ",pending="
                    + pending + "}";
        }
    }

    private final CredentialBackend backend;
    private final CredentialCoordinator coord;
    private volatile boolean closed;

    public CredentialStore(CredentialBackend backend) {
        this.backend = backend;
        this.coord = CredentialCoordinator.acquire(backend.storageId());
    }

    /** Сериализованная проверка/миграция — на общем рабочем потоке хранилища. */
    public Future<Resolution> resolveAsync() {
        return coord.submit(this::resolve);
    }

    /**
     * Закрытие handle: состояние координатора сохраняется, общий worker
     * завершается мягко, когда закрыт последний handle (без прерывания
     * выполняющегося commit).
     */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            coord.release();
        }
    }

    private Resolution resolve() {
        Source enc = snapshot(Role.ENCRYPTED);
        Source plain = snapshot(Role.PLAIN);
        Source def = snapshot(Role.DEFAULT);
        Source[] snap = {enc, plain, def};

        // Незавершённая операция приоритетнее нового аудита кеша:
        // очищенный в кеше источник не отменяет долг подтвердить очистку,
        // грязный encrypted-кеш не делает запись назначения подтверждённой.
        PendingOp op = coord.pending;
        if (op != null) {
            return resume(op, snap);
        }

        Outcome o = CredentialAudit.check(enc, plain, def);
        switch (o.verdict) {
            case READY_SECURED:
                return new Resolution(Status.READY, o.reason, Pending.NONE,
                        enc.token.string, enc.effectiveChatId(),
                        enc.effectiveAlertChatId());
            case EMPTY:
                return new Resolution(Status.EMPTY, o.reason, Pending.NONE,
                        "", 0, 0);
            case MIGRATION_REQUIRED:
                return startPlan(o, snap);
            default: // CONFLICT / UNAVAILABLE / CORRUPT — ничего не писать
                return new Resolution(Status.BLOCKED, o.reason,
                        pendingOf(coord.pending), "", 0, 0);
        }
    }

    /** Новая однозначная операция: запись назначения → точечные очистки. */
    private Resolution startPlan(Outcome o, Source[] snap) {
        Plan plan = o.plan;
        if (plan == null) {
            return new Resolution(Status.BLOCKED, o.reason, Pending.NONE,
                    "", 0, 0);
        }
        PendingOp op = new PendingOp(snap, plan);
        op.pendingClears.addAll(plan.clearSources); // всё плановое — долг
        coord.pending = op;

        if (!plan.token.isEmpty() && !plan.alreadyEncrypted) {
            if (!put(plan)) {
                op.destinationUnconfirmed = true;
                return new Resolution(Status.INCOMPLETE, o.reason,
                        Pending.DESTINATION_WRITE, "", 0, 0);
            }
        }
        for (Role role : new ArrayList<>(op.pendingClears)) {
            Resolution r = clearOnce(op, role, o.reason);
            if (r != null) {
                return r;
            }
            op.pendingClears.remove(role);
        }
        return finish(o.reason, plan);
    }

    /**
     * Продолжение незавершённой операции любым handle этого хранилища:
     * сначала поверка источников относительно исходного снимка, затем
     * повтор неподтверждённых шагов.
     */
    private Resolution resume(PendingOp op, Source[] snap) {
        for (Role role : Role.values()) {
            Source stored = sourceOf(op.snapshot, role);
            Source cur = sourceOf(snap, role);
            if (op.pendingClears.contains(role)) {
                // Цель очистки: старые реквизиты либо пропавшие из кеша —
                // ок; иные непустые значения — чужие данные, не удалять.
                if (!sameCredentials(stored, cur) && hasCredentials(cur)) {
                    return changed();
                }
            } else if (role == Role.ENCRYPTED) {
                // Назначение: исходное состояние или значения нашего плана
                // (неподтверждённая запись могла остаться в кеше).
                if (!sameCredentials(stored, cur)
                        && !matchesPlan(cur, op.plan)) {
                    return changed();
                }
            } else if (!sameCredentials(stored, cur)) {
                return changed();
            }
        }

        if (op.destinationUnconfirmed) {
            if (!put(op.plan)) {
                return new Resolution(Status.INCOMPLETE, Reason.NONE,
                        Pending.DESTINATION_WRITE, "", 0, 0);
            }
            op.destinationUnconfirmed = false;
        }
        for (Role role : new ArrayList<>(op.pendingClears)) {
            Resolution r = clearOnce(op, role, Reason.NONE);
            if (r != null) {
                return r;
            }
            op.pendingClears.remove(role);
        }
        return finish(Reason.NONE, op.plan);
    }

    private Resolution changed() {
        // Новые реквизиты не затираются старым планом: операция
        // останавливается, требуется новая проверка. Состояние сохраняется.
        return new Resolution(Status.INCOMPLETE, Reason.NONE,
                Pending.SOURCE_CHANGED, "", 0, 0);
    }

    /** Одна очистка с предварительной проверкой неизменности источника. */
    private Resolution clearOnce(PendingOp op, Role role, Reason reason) {
        Source cur = backendSnapshotSafe(role);
        Source stored = sourceOf(op.snapshot, role);
        // Если источник успели наполнить новыми реквизитами — не удалять.
        if (!sameCredentials(stored, cur) && hasCredentials(cur)) {
            return changed();
        }
        if (!remove(role)) {
            return new Resolution(Status.INCOMPLETE, reason,
                    Pending.SOURCE_CLEAR, "", 0, 0);
        }
        return null;
    }

    private Resolution finish(Reason reason, Plan plan) {
        Source enc = snapshot(Role.ENCRYPTED);
        Outcome after = CredentialAudit.check(enc,
                snapshot(Role.PLAIN), snapshot(Role.DEFAULT));
        if (after.verdict == Verdict.READY_SECURED) {
            coord.pending = null;
            return new Resolution(Status.READY, after.reason, Pending.NONE,
                    enc.token.string, enc.effectiveChatId(),
                    enc.effectiveAlertChatId());
        }
        if (after.verdict == Verdict.EMPTY
                && (plan == null || plan.token.isEmpty())) {
            coord.pending = null;
            return new Resolution(Status.EMPTY, after.reason, Pending.NONE,
                    "", 0, 0);
        }
        // Итог не соответствует завершённой операции — не завершена.
        return new Resolution(Status.INCOMPLETE, after.reason,
                Pending.SOURCE_CLEAR, "", 0, 0);
    }

    private boolean put(Plan plan) {
        try {
            return backend.putCredentials(plan.token, plan.chatId,
                    plan.alertChatId);
        } catch (RuntimeException e) {
            return false; // как commit=false: кеш грязный, диск не подтверждён
        }
    }

    private boolean remove(Role role) {
        try {
            return backend.removeCredentials(role);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Source snapshot(Role role) {
        return backendSnapshotSafe(role);
    }

    private Source backendSnapshotSafe(Role role) {
        try {
            Source s = backend.snapshot(role);
            return s == null ? Source.corrupt(role) : s;
        } catch (RuntimeException e) {
            return Source.corrupt(role);
        }
    }

    private static Pending pendingOf(PendingOp op) {
        if (op == null) {
            return Pending.NONE;
        }
        if (op.destinationUnconfirmed) {
            return Pending.DESTINATION_WRITE;
        }
        return Pending.SOURCE_CLEAR;
    }

    /** Источник содержит какие-либо ключи реквизитов. */
    private static boolean hasCredentials(Source s) {
        return s != null && s.hasKey();
    }

    /** Текущие реквизиты источника равны значениям плана (наша запись). */
    private static boolean matchesPlan(Source s, Plan plan) {
        return s != null && plan != null
                && s.token.kind == Entry.Kind.PRESENT
                && s.token.string.equals(plan.token)
                && s.effectiveChatId() == plan.chatId
                && s.effectiveAlertChatId() == plan.alertChatId;
    }

    private static Source sourceOf(Source[] snapshot, Role role) {
        for (Source s : snapshot) {
            if (s != null && s.role == role) {
                return s;
            }
        }
        return null;
    }

    private static boolean sameCredentials(Source a, Source b) {
        if (a == null || b == null) {
            return false;
        }
        return sameEntry(a.token, b.token) && sameEntry(a.chatId, b.chatId)
                && sameEntry(a.alertChatId, b.alertChatId);
    }

    private static boolean sameEntry(Entry a, Entry b) {
        return a.kind == b.kind && a.type == b.type
                && a.string.equals(b.string) && a.number == b.number;
    }
}
