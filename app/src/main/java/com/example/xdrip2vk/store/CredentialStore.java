package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit;
import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Outcome;
import com.example.xdrip2vk.core.CredentialAudit.Plan;
import com.example.xdrip2vk.core.CredentialAudit.Read;
import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.CredentialAudit.Verdict;
import com.example.xdrip2vk.store.CredentialCoordinator.PendingOp;

import java.util.ArrayList;
import java.util.concurrent.Future;

/**
 * Исполнитель слоя реквизитов (задания 2Г.3Б/3Б.1/3Б.2): читает снимки
 * через {@link CredentialBackend}, решает настоящим
 * {@link CredentialAudit} и выполняет только однозначный план.
 * Не подключён к действующим TokenStorage/Activity/receivers/сервису.
 *
 * Незавершённая операция — конкретная: в {@link PendingOp} раздельно
 * учитываются подтверждённые (confirmedClears) и неподтверждённые
 * (pendingClears) шаги. Подтверждённо очищенный источник ожидается
 * пустым; неподтверждённый — как исходник либо пустой кеш, и повтор
 * реально вызывает remove. UNAVAILABLE/CORRUPT снимок не доказательство
 * пустоты: зависимые записи и удаления не выполняются, причина
 * фиксированная (SOURCE_UNAVAILABLE/SOURCE_CORRUPT), операция сохранена.
 *
 * commit()==true — единственное подтверждение; read-back не доверяется.
 * Владение — {@link CredentialCoordinator} по storageId: новый handle или
 * backend — не новый процесс, защиту не обходит.
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
        SOURCE_CHANGED,
        /** Источник недоступен/повреждён — состояние недостоверно. */
        UNREADABLE
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
    private boolean closed; // под synchronized(this)

    public CredentialStore(CredentialBackend backend) {
        this.backend = backend;
        this.coord = CredentialCoordinator.acquire(backend.storageId());
    }

    /**
     * Сериализованная проверка/миграция на общем worker хранилища.
     * После close() — отклоняется до постановки задачи; гонка с close()
     * согласована: задача либо принята и корректно завершится, либо
     * отклонена — обхода владения координатором нет.
     */
    public synchronized Future<Resolution> resolveAsync() {
        if (closed) {
            throw new IllegalStateException("credential store closed");
        }
        return coord.submit(this::resolve);
    }

    /**
     * Закрытие handle: идемпотентно и потокобезопасно; состояние
     * координатора сохраняется, общий worker продолжает поставленные
     * задачи — выполняющийся commit не прерывается.
     */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        coord.release();
    }

    private Resolution resolve() {
        Source enc = snapshot(Role.ENCRYPTED);
        Source plain = snapshot(Role.PLAIN);
        Source def = snapshot(Role.DEFAULT);
        Source[] snap = {enc, plain, def};

        // Незавершённая операция приоритетнее нового аудита кеша.
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
                        Pending.NONE, "", 0, 0);
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
        // Однозначная существующая encrypted-копия = подтверждённое назначение.
        op.destinationConfirmed =
                !plan.token.isEmpty() && plan.alreadyEncrypted;
        coord.pending = op;

        if (!plan.token.isEmpty() && !plan.alreadyEncrypted) {
            if (!put(plan)) {
                op.destinationUnconfirmed = true;
                return new Resolution(Status.INCOMPLETE, o.reason,
                        Pending.DESTINATION_WRITE, "", 0, 0);
            }
            op.destinationConfirmed = true;
        }
        for (Role role : new ArrayList<>(op.pendingClears)) {
            Resolution r = clearOnce(op, role, o.reason);
            if (r != null) {
                return r;
            }
            op.pendingClears.remove(role);
            op.confirmedClears.add(role);
        }
        return finish(o.reason, plan);
    }

    /**
     * Продолжение незавершённой операции любым handle этого хранилища:
     * поверка источников относительно ожидаемого состояния, затем повтор
     * неподтверждённых шагов.
     */
    private Resolution resume(PendingOp op, Source[] snap) {
        for (Role role : Role.values()) {
            Source cur = sourceOf(snap, role);
            Resolution bad = unreadable(cur, Pending.UNREADABLE);
            if (bad != null) {
                return bad;
            }
            Source stored = sourceOf(op.snapshot, role);
            if (op.confirmedClears.contains(role)) {
                // Успешно очищен: новые реквизиты — чужие, не удалять.
                if (hasCredentials(cur)) {
                    return changed();
                }
            } else if (op.pendingClears.contains(role)) {
                // Неподтверждённая очистка: исходник или пустой кеш — ок;
                // иные значения — чужие данные, не удалять.
                if (!sameCredentials(stored, cur) && hasCredentials(cur)) {
                    return changed();
                }
            } else if (role == Role.ENCRYPTED) {
                if (op.plan != null && !op.plan.token.isEmpty()
                        && op.destinationConfirmed) {
                    // Подтверждённое назначение: исходное пустое состояние
                    // больше недопустимо — только ожидаемые реквизиты.
                    if (!matchesPlan(cur, op.plan)) {
                        return destinationMismatch();
                    }
                } else if (!sameCredentials(stored, cur)
                        && !matchesPlan(cur, op.plan)) {
                    // Неподтверждённая запись: исходник или наш грязный кеш.
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
            op.destinationConfirmed = true;
        }
        for (Role role : new ArrayList<>(op.pendingClears)) {
            Resolution r = clearOnce(op, role, Reason.NONE);
            if (r != null) {
                return r;
            }
            op.pendingClears.remove(role);
            op.confirmedClears.add(role);
        }
        return finish(Reason.NONE, op.plan);
    }

    private Resolution changed() {
        // Новые реквизиты не затираются и не удаляются старым планом:
        // операция останавливается, состояние сохраняется.
        return new Resolution(Status.INCOMPLETE, Reason.NONE,
                Pending.SOURCE_CHANGED, "", 0, 0);
    }

    private Resolution destinationMismatch() {
        // Назначение исчезло или изменилось после подтверждения: очистку
        // не продолжать, старым планом не восстанавливать — это может
        // быть отдельное изменение. PendingOp сохраняется.
        return new Resolution(Status.INCOMPLETE, Reason.DESTINATION_MISMATCH,
                Pending.SOURCE_CHANGED, "", 0, 0);
    }

    /**
     * Проверка назначения перед КАЖДОЙ очисткой legacy для плана с
     * непустым токеном: подтверждено, читаемо, типы корректны и
     * реквизиты совпадают с ожидаемыми. Это дополнительная проверка —
     * она не заменяет commit и не делает грязный кеш подтверждённым.
     */
    private Resolution checkDestination(PendingOp op) {
        Plan plan = op.plan;
        if (plan == null || plan.token.isEmpty()) {
            return null;
        }
        if (!op.destinationConfirmed) {
            return new Resolution(Status.INCOMPLETE, Reason.NONE,
                    Pending.DESTINATION_WRITE, "", 0, 0);
        }
        Source enc = backendSnapshotSafe(Role.ENCRYPTED);
        Resolution bad = unreadable(enc, Pending.UNREADABLE);
        if (bad != null) {
            return bad;
        }
        if (!matchesPlan(enc, plan)) {
            return destinationMismatch();
        }
        return null;
    }

    /**
     * Снимок недостоверен: UNAVAILABLE/CORRUPT/ошибочный тип или чужая
     * роль не доказывают пустоту — зависимые записи и удаления запрещены.
     */
    private Resolution unreadable(Source cur, Pending phase) {
        if (cur == null) {
            return new Resolution(Status.INCOMPLETE, Reason.SOURCE_CORRUPT,
                    phase, "", 0, 0);
        }
        if (cur.read == Read.UNAVAILABLE) {
            return new Resolution(Status.INCOMPLETE,
                    Reason.SOURCE_UNAVAILABLE, phase, "", 0, 0);
        }
        if (cur.read == Read.CORRUPT || cur.hasFieldTypeMismatch()) {
            return new Resolution(Status.INCOMPLETE, Reason.SOURCE_CORRUPT,
                    phase, "", 0, 0);
        }
        return null;
    }

    /** Одна очистка с проверкой достоверности и неизменности источника. */
    private Resolution clearOnce(PendingOp op, Role role, Reason reason) {
        Source cur = backendSnapshotSafe(role);
        Resolution bad = unreadable(cur, Pending.UNREADABLE);
        if (bad != null) {
            return bad;
        }
        if (cur.role != role) {
            return new Resolution(Status.INCOMPLETE, Reason.SOURCE_CORRUPT,
                    Pending.SOURCE_CLEAR, "", 0, 0);
        }
        Source stored = sourceOf(op.snapshot, role);
        // Исходные реквизиты или пустой кеш после прошлого отказа — ок;
        // новые значения — не удалять.
        if (!sameCredentials(stored, cur) && hasCredentials(cur)) {
            return changed();
        }
        // Назначение проверяется перед каждой очисткой: если оно исчезло,
        // удаление legacy уничтожило бы последнюю копию токена.
        Resolution dest = checkDestination(op);
        if (dest != null) {
            return dest;
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
            // READY только с реквизитами именно этой операции: иной
            // появившийся encrypted-токен результатом не признаём.
            if (plan != null && !plan.token.isEmpty()
                    && !matchesPlan(enc, plan)) {
                return destinationMismatch();
            }
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
        if (after.verdict == Verdict.UNAVAILABLE) {
            return new Resolution(Status.INCOMPLETE, after.reason,
                    Pending.UNREADABLE, "", 0, 0);
        }
        if (after.verdict == Verdict.CORRUPT) {
            return new Resolution(Status.INCOMPLETE, after.reason,
                    Pending.UNREADABLE, "", 0, 0);
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
            if (s == null || s.role != role) {
                return Source.corrupt(role);
            }
            return s;
        } catch (RuntimeException e) {
            return Source.corrupt(role);
        }
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
