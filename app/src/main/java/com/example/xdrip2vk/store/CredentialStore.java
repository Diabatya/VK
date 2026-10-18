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
import com.example.xdrip2vk.store.CredentialBackend.ServiceState;
import com.example.xdrip2vk.store.CredentialCoordinator.OpKind;
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
        UNREADABLE,
        /** Подтверждённое удаление не подтверждено commit. */
        DELETE
    }

    /** Фиксированные коды исходов — без значений. */
    public enum Status {
        /** Однозначные защищённые реквизиты — можно использовать. */
        READY,
        /** Реквизитов нет. */
        EMPTY,
        /** Подтверждённое удаление пользователем — отправки/миграции нет. */
        DELETED,
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
     * Подтверждённое сохранение полного набора реквизитов в encrypted
     * одним commit вместе со служебным состоянием. Низкоуровневая
     * команда хранения: не подтверждает владельца VK и не даёт права
     * отправлять старую очередь новым аккаунтом. Разрешена только из
     * READY/EMPTY/DELETED.
     */
    public synchronized Future<Resolution> saveAsync(String token,
                                                     long chatId,
                                                     long alertChatId) {
        if (closed) {
            throw new IllegalStateException("credential store closed");
        }
        return coord.submit(() -> doSave(token, chatId, alertChatId));
    }

    /** Подтверждённое удаление реквизитов и запись «удалено пользователем». */
    public synchronized Future<Resolution> deleteAsync() {
        if (closed) {
            throw new IllegalStateException("credential store closed");
        }
        return coord.submit(this::doDelete);
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

        ServiceState meta = serviceState(Role.ENCRYPTED);
        if (meta == ServiceState.INVALID) {
            return new Resolution(Status.BLOCKED,
                    Reason.SERVICE_STATE_INVALID, Pending.NONE, "", 0, 0);
        }
        if (meta == ServiceState.DELETED) {
            // Подтверждённое удаление: миграции legacy-остатков нет,
            // отправка запрещена, воскрешать токен нельзя.
            return deleted(Pending.NONE);
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
                return startPlan(o, snap, meta);
            default: // CONFLICT / UNAVAILABLE / CORRUPT — ничего не писать
                return new Resolution(Status.BLOCKED, o.reason,
                        Pending.NONE, "", 0, 0);
        }
    }

    /** Новая однозначная операция: запись назначения → точечные очистки. */
    private Resolution startPlan(Outcome o, Source[] snap,
                                 ServiceState meta) {
        Plan plan = o.plan;
        if (plan == null) {
            return new Resolution(Status.BLOCKED, o.reason, Pending.NONE,
                    "", 0, 0);
        }
        // Запись назначения ставит маркер ACTIVE тем же commit; план без
        // записи (alreadyEncrypted/осиротевшие ключи) маркер не меняет.
        boolean writesMarker = !plan.token.isEmpty() && !plan.alreadyEncrypted;
        PendingOp op = new PendingOp(OpKind.MIGRATION, snap, plan, meta,
                writesMarker ? ServiceState.ACTIVE : null);
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
        if (op.kind == OpKind.SAVE) {
            return resumeSave(op, snap);
        }
        if (op.kind == OpKind.DELETE) {
            return resumeDelete(op, snap);
        }
        return resumeMigration(op, snap);
    }

    /** Продолжение неподтверждённого сохранения: реальный повтор записи. */
    private Resolution resumeSave(PendingOp op, Source[] snap) {
        Resolution m = markerChanged(op);
        if (m != null) {
            return m;
        }
        for (Role role : Role.values()) {
            Source cur = sourceOf(snap, role);
            Resolution bad = unreadable(cur, Pending.UNREADABLE);
            if (bad != null) {
                return bad;
            }
            Source stored = sourceOf(op.snapshot, role);
            if (role == Role.ENCRYPTED) {
                if (op.destinationConfirmed) {
                    // Запись подтверждена: допустимы только реквизиты
                    // плана — исходное состояние снова недопустимо и
                    // повторной записи ради исправления не будет.
                    if (!matchesPlan(cur, op.plan)) {
                        return destinationMismatch();
                    }
                } else if (!sameCredentials(stored, cur)
                        && !matchesPlan(cur, op.plan)) {
                    // Исходник или наши новые реквизиты из грязного кеша.
                    return changed();
                }
            } else if (!sameCredentials(stored, cur)) {
                return changed();
            }
        }
        if (!op.destinationConfirmed) {
            m = markerChanged(op); // перед повторным commit
            if (m != null) {
                return m;
            }
            if (!put(op.plan)) {
                return new Resolution(Status.INCOMPLETE, Reason.NONE,
                        Pending.DESTINATION_WRITE, "", 0, 0);
            }
            op.destinationConfirmed = true;
        }
        return finish(Reason.NONE, op.plan);
    }

    /** Продолжение неподтверждённого удаления: реальный повтор commit. */
    private Resolution resumeDelete(PendingOp op, Source[] snap) {
        Resolution m = markerChanged(op);
        if (m != null) {
            return m;
        }
        if (!op.deleteConfirmed) {
            for (Role role : Role.values()) {
                Source cur = sourceOf(snap, role);
                Resolution bad = unreadable(cur, Pending.UNREADABLE);
                if (bad != null) {
                    return bad;
                }
                Source stored = sourceOf(op.snapshot, role);
                // enc может быть исходником либо уже пустым кешем после
                // неподтверждённого delete; новые реквизиты — не трогать.
                if (!sameCredentials(stored, cur) && hasCredentials(cur)) {
                    return changed();
                }
            }
            m = markerChanged(op); // перед повторным commit
            if (m != null) {
                return m;
            }
            if (!backendDelete()) {
                return new Resolution(Status.INCOMPLETE, Reason.NONE,
                        Pending.DELETE, "", 0, 0);
            }
            op.deleteConfirmed = true;
        }
        // После подтверждения — только проверка результата: появившиеся
        // реквизиты повторным удалением не трогаем.
        return confirmDeleted();
    }

    private Resolution resumeMigration(PendingOp op, Source[] snap) {
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

        // Маркер — после поверки источников (расхождение реквизитов
        // важнее), но до любой зависимой записи.
        Resolution m = markerChanged(op);
        if (m != null) {
            return m;
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

    // --- команды сохранения и удаления (2Г.3В.1) ---

    /**
     * Допустимые исходные состояния: READY, EMPTY или подтверждённое
     * удаление. При CONFLICT/UNAVAILABLE/CORRUPT/незавершённой операции
     * запись не выполняется — скрытого выбора источника нет.
     */
    private Resolution doSave(String token, long chatId, long alertChatId) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            // Пустой токен — не сохранение: для этого отдельная команда.
            return new Resolution(Status.BLOCKED, Reason.INVALID_INPUT,
                    Pending.NONE, "", 0, 0);
        }
        Source[] snap = snapshots();
        PendingOp op = coord.pending;
        if (op != null) {
            return incompleteFor(op);
        }
        ServiceState meta = serviceState(Role.ENCRYPTED);
        if (meta == ServiceState.INVALID) {
            return new Resolution(Status.BLOCKED,
                    Reason.SERVICE_STATE_INVALID, Pending.NONE, "", 0, 0);
        }
        if (meta == ServiceState.DELETED) {
            // Подтверждённое удаление разрешает явное сохранение, но не
            // освобождает от проверки снимков: encrypted обязан читаться
            // и быть пустым, legacy — доступны и без реквизитов.
            Resolution bad = deletedStateCheck(snap);
            if (bad != null) {
                return bad;
            }
        } else {
            Outcome o = check(snap);
            if (o.verdict != Verdict.READY_SECURED
                    && o.verdict != Verdict.EMPTY) {
                return new Resolution(Status.BLOCKED, o.reason,
                        Pending.NONE, "", 0, 0);
            }
        }
        PendingOp save = new PendingOp(OpKind.SAVE, snap,
                plan(t, chatId, alertChatId), meta, ServiceState.ACTIVE);
        coord.pending = save;
        if (!put(save.plan)) {
            save.destinationUnconfirmed = true;
            return new Resolution(Status.INCOMPLETE, Reason.NONE,
                    Pending.DESTINATION_WRITE, "", 0, 0);
        }
        save.destinationConfirmed = true;
        return finish(Reason.NONE, save.plan);
    }

    private Resolution doDelete() {
        Source[] snap = snapshots();
        PendingOp op = coord.pending;
        if (op != null) {
            return incompleteFor(op);
        }
        ServiceState meta = serviceState(Role.ENCRYPTED);
        if (meta == ServiceState.INVALID) {
            return new Resolution(Status.BLOCKED,
                    Reason.SERVICE_STATE_INVALID, Pending.NONE, "", 0, 0);
        }
        if (meta == ServiceState.DELETED) {
            return deleted(Pending.NONE); // идемпотентно
        }
        Outcome o = check(snap);
        if (o.verdict != Verdict.READY_SECURED
                && o.verdict != Verdict.EMPTY) {
            return new Resolution(Status.BLOCKED, o.reason, Pending.NONE,
                    "", 0, 0);
        }
        PendingOp del = new PendingOp(OpKind.DELETE, snap, null, meta,
                ServiceState.DELETED);
        coord.pending = del;
        if (!backendDelete()) {
            return new Resolution(Status.INCOMPLETE, Reason.NONE,
                    Pending.DELETE, "", 0, 0);
        }
        del.deleteConfirmed = true;
        return confirmDeleted();
    }

    private Resolution incompleteFor(PendingOp op) {
        Pending p = op.kind == OpKind.DELETE ? Pending.DELETE
                : op.destinationUnconfirmed ? Pending.DESTINATION_WRITE
                : Pending.SOURCE_CLEAR;
        return new Resolution(Status.INCOMPLETE, Reason.NONE, p, "", 0, 0);
    }

    private Resolution deleted(Pending pending) {
        return new Resolution(Status.DELETED, Reason.DELETED_BY_USER,
                pending, "", 0, 0);
    }

    private Source[] snapshots() {
        return new Source[]{snapshot(Role.ENCRYPTED),
                snapshot(Role.PLAIN), snapshot(Role.DEFAULT)};
    }

    private Outcome check(Source[] snap) {
        return CredentialAudit.check(snap[0], snap[1], snap[2]);
    }

    private ServiceState serviceState(Role role) {
        try {
            ServiceState s = backend.serviceState(role);
            return s == null ? ServiceState.INVALID : s;
        } catch (RuntimeException e) {
            return ServiceState.INVALID;
        }
    }

    private Plan plan(String token, long chatId, long alertChatId) {
        return Plan.of(token, chatId, alertChatId,
                java.util.Collections.<Role>emptyList(), false);
    }

    /**
     * Маркер — часть состояния операции. Допустимы исходное значение и
     * значение, которое пишет commit этой операции (грязный кеш после
     * commit=false сам по себе подтверждением не является). INVALID,
     * ошибка чтения и постороннее изменение блокируют зависимые шаги.
     */
    private Resolution markerChanged(PendingOp op) {
        ServiceState cur = serviceState(Role.ENCRYPTED);
        if (cur == ServiceState.INVALID) {
            return new Resolution(Status.INCOMPLETE,
                    Reason.SERVICE_STATE_INVALID, Pending.UNREADABLE,
                    "", 0, 0);
        }
        // Фазы: до подтверждения записи допустимы исходный маркер и
        // значение нашего commit (грязный кеш — не подтверждение, commit
        // повторяется реально). После commit()==true допустимо только
        // ожидаемое состояние после записи: возврата к исходной фазе и
        // повторной записи ради исправления расхождения нет.
        boolean confirmed = op.kind == OpKind.DELETE
                ? op.deleteConfirmed : op.destinationConfirmed;
        ServiceState expected = op.writtenState != null
                ? op.writtenState : op.initialState;
        boolean ok = confirmed
                ? cur == expected
                : (cur == op.initialState || cur == op.writtenState);
        if (ok) {
            return null;
        }
        // Новый маркер (в т.ч. подтверждённое удаление) — постороннее
        // изменение: не снимать и не перезаписывать старым планом.
        return new Resolution(Status.INCOMPLETE,
                Reason.SERVICE_STATE_CHANGED, Pending.SOURCE_CHANGED,
                "", 0, 0);
    }

    /**
     * DELETED — допустимый источник явного сохранения, но не основание
     * пропустить снимки: все источники читаемы и корректны по типам,
     * реквизитов ни в encrypted, ни в legacy быть не должно. Иначе —
     * несогласованное состояние, автоматически не исправляется.
     */
    private Resolution deletedStateCheck(Source[] snap) {
        for (Role role : Role.values()) {
            Source cur = sourceOf(snap, role);
            if (cur == null || cur.role != role) {
                return new Resolution(Status.BLOCKED, Reason.SOURCE_CORRUPT,
                        Pending.NONE, "", 0, 0);
            }
            if (cur.read == Read.UNAVAILABLE) {
                return new Resolution(Status.BLOCKED,
                        Reason.SOURCE_UNAVAILABLE, Pending.NONE, "", 0, 0);
            }
            if (cur.read == Read.CORRUPT || cur.hasFieldTypeMismatch()) {
                return new Resolution(Status.BLOCKED, Reason.SOURCE_CORRUPT,
                        Pending.NONE, "", 0, 0);
            }
        }
        for (Role role : Role.values()) {
            if (hasCredentials(sourceOf(snap, role))) {
                return new Resolution(Status.BLOCKED,
                        Reason.SERVICE_STATE_MISMATCH, Pending.NONE,
                        "", 0, 0);
            }
        }
        return null;
    }

    /**
     * Подтверждение delete commit'ом: итог допустим только при читаемом
     * маркере DELETED. commit()==true — единственное подтверждение, но
     * посторонняя замена маркера после него результатом не признаётся.
     */
    private Resolution confirmDeleted() {
        ServiceState cur = serviceState(Role.ENCRYPTED);
        if (cur == ServiceState.INVALID) {
            return new Resolution(Status.INCOMPLETE,
                    Reason.SERVICE_STATE_INVALID, Pending.UNREADABLE,
                    "", 0, 0);
        }
        if (cur != ServiceState.DELETED) {
            return new Resolution(Status.INCOMPLETE,
                    Reason.SERVICE_STATE_CHANGED, Pending.SOURCE_CHANGED,
                    "", 0, 0);
        }
        // Результат обязан соответствовать удалению: реквизитов в
        // encrypted нет. Появившиеся значения — несогласованность,
        // повторным удалением не исправляем.
        Source enc = backendSnapshotSafe(Role.ENCRYPTED);
        Resolution bad = unreadable(enc, Pending.UNREADABLE);
        if (bad != null) {
            return bad;
        }
        if (hasCredentials(enc)) {
            return new Resolution(Status.INCOMPLETE,
                    Reason.SERVICE_STATE_MISMATCH, Pending.SOURCE_CHANGED,
                    "", 0, 0);
        }
        coord.pending = null;
        return deleted(Pending.NONE);
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
        // Маркер проверяется перед каждой очисткой: новое подтверждённое
        // удаление или INVALID старым планом не снимаются.
        Resolution m = markerChanged(op);
        if (m != null) {
            return m;
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
        // Перед публикацией результата маркер обязан быть ожидаемым:
        // записанным этой операцией либо неизменным исходным. Неизвестная
        // версия и постороннее изменение блокируют завершение.
        PendingOp op = coord.pending;
        if (op != null) {
            ServiceState cur = serviceState(Role.ENCRYPTED);
            if (cur == ServiceState.INVALID) {
                return new Resolution(Status.INCOMPLETE,
                        Reason.SERVICE_STATE_INVALID, Pending.UNREADABLE,
                        "", 0, 0);
            }
            ServiceState expected = op.writtenState != null
                    ? op.writtenState : op.initialState;
            if (cur != expected) {
                return new Resolution(Status.INCOMPLETE,
                        Reason.SERVICE_STATE_CHANGED, Pending.SOURCE_CHANGED,
                        "", 0, 0);
            }
        }
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
                    plan.alertChatId, CredentialBackend.STATE_ACTIVE);
        } catch (RuntimeException e) {
            return false; // как commit=false: кеш грязный, диск не подтверждён
        }
    }

    private boolean backendDelete() {
        try {
            return backend.deleteCredentials();
        } catch (RuntimeException e) {
            return false;
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
