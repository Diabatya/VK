package com.example.xdrip2vk.store;

import com.example.xdrip2vk.core.CredentialAudit;
import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Outcome;
import com.example.xdrip2vk.core.CredentialAudit.Plan;
import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.CredentialAudit.Verdict;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Исполнитель слоя реквизитов (задание 2Г.3Б): читает снимки через
 * подменяемый {@link CredentialBackend}, принимает решение настоящим
 * {@link CredentialAudit} и выполняет только однозначный план.
 *
 * Не подключён к действующим TokenStorage/Activity/receivers/сервису —
 * вызывается тестами. Автоматической миграции при старте нет.
 *
 * Порядок переноса непустого токена:
 *   снимки → commit записи назначения → перепроверка источника →
 *   commit очистки каждого источника → повторная проверка итога.
 *
 * commit()==true — единственное подтверждение записи: read-back из
 * того же SharedPreferences не доверяется (commit=false пачкает кеш).
 * Незавершённая операция хранится как in-process pendingState; в новом
 * процессе восстановление выполняется из реально сохранённых данных —
 * повторный check видит подтверждённые шаги и продолжает план.
 * Отдельный marker в default prefs протоколом не заменяется.
 *
 * Все операции сериализуются одним рабочим потоком — диск и Keystore
 * вне главного потока. Секреты не выводятся в диагностику.
 */
public final class CredentialStore {

    /** Фаза незавершённой операции в процессе. */
    public enum Pending {
        NONE,
        /** Запись назначения не подтверждена commit. */
        DESTINATION_WRITE,
        /** Очистка одного или нескольких источников не подтверждена. */
        SOURCE_CLEAR,
        /** Источник изменился до очистки — требуется новая проверка. */
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
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r ->
                    new Thread(r, "credential-store"));

    /** In-process состояние незавершённой операции (под lock worker'а). */
    private Pending pendingState = Pending.NONE;
    /** Запись назначения не подтверждена — alreadyEncrypted не доверять. */
    private boolean destinationWriteUnconfirmed;

    public CredentialStore(CredentialBackend backend) {
        this.backend = backend;
    }

    /** Сериализованная проверка/миграция — на рабочем потоке. */
    public Future<Resolution> resolveAsync() {
        return worker.submit(this::resolve);
    }

    /** Для будущего подключения: все записи реквизитов — через этот worker. */
    public ExecutorService executor() {
        return worker;
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private Resolution resolve() {
        Source enc = backend.snapshot(Role.ENCRYPTED);
        Source plain = backend.snapshot(Role.PLAIN);
        Source def = backend.snapshot(Role.DEFAULT);
        Outcome o = CredentialAudit.check(enc, plain, def);
        Source[] snapshot = {enc, plain, def};

        switch (o.verdict) {
            case READY_SECURED:
                pendingState = Pending.NONE;
                return new Resolution(Status.READY, o.reason, Pending.NONE,
                        enc.token.string, enc.effectiveChatId(),
                        enc.effectiveAlertChatId());
            case EMPTY:
                pendingState = Pending.NONE;
                return new Resolution(Status.EMPTY, o.reason, Pending.NONE,
                        "", 0, 0);
            case MIGRATION_REQUIRED:
                return executePlan(o, snapshot);
            default: // CONFLICT / UNAVAILABLE / CORRUPT — ничего не писать
                return new Resolution(Status.BLOCKED, o.reason, pendingState,
                        "", 0, 0);
        }
    }

    private Resolution executePlan(Outcome o, Source[] snapshot) {
        Plan plan = o.plan;
        if (plan == null) {
            return new Resolution(Status.BLOCKED, o.reason, pendingState,
                    "", 0, 0);
        }

        // 1. Запись назначения одним commit — только для непустого токена.
        //    alreadyEncrypted доверяется лишь при отсутствии в процессе
        //    неподтверждённой записи: грязный кеш успехом не считается.
        if (!plan.token.isEmpty()
                && (!plan.alreadyEncrypted || destinationWriteUnconfirmed)) {
            if (!backend.putCredentials(plan.token, plan.chatId,
                    plan.alertChatId)) {
                destinationWriteUnconfirmed = true;
                pendingState = Pending.DESTINATION_WRITE;
                return new Resolution(Status.INCOMPLETE, o.reason,
                        pendingState, "", 0, 0);
            }
            destinationWriteUnconfirmed = false;
        }

        // 2. Очистка источников — каждая своим commit, после перепроверки,
        //    что реквизиты источника не изменились со времени снимка.
        for (Role role : plan.clearSources) {
            if (!sameCredentials(sourceOf(snapshot, role),
                    backend.snapshot(role))) {
                pendingState = Pending.SOURCE_CHANGED;
                return new Resolution(Status.INCOMPLETE, o.reason,
                        pendingState, "", 0, 0);
            }
            if (!backend.removeCredentials(role)) {
                pendingState = Pending.SOURCE_CLEAR;
                return new Resolution(Status.INCOMPLETE, o.reason,
                        pendingState, "", 0, 0);
            }
        }

        // 3. Повторная проверка итогового состояния.
        Source enc = backend.snapshot(Role.ENCRYPTED);
        Outcome after = CredentialAudit.check(enc,
                backend.snapshot(Role.PLAIN), backend.snapshot(Role.DEFAULT));
        if (after.verdict == Verdict.READY_SECURED) {
            pendingState = Pending.NONE;
            return new Resolution(Status.READY, after.reason, Pending.NONE,
                    enc.token.string, enc.effectiveChatId(),
                    enc.effectiveAlertChatId());
        }
        if (after.verdict == Verdict.EMPTY) {
            pendingState = Pending.NONE;
            return new Resolution(Status.EMPTY, after.reason, Pending.NONE,
                    "", 0, 0);
        }
        // Очистка частично не сохранилась либо появились новые данные —
        // миграция не объявляется завершённой.
        pendingState = Pending.SOURCE_CLEAR;
        return new Resolution(Status.INCOMPLETE, after.reason, pendingState,
                "", 0, 0);
    }

    private static Source sourceOf(Source[] snapshot, Role role) {
        for (Source s : snapshot) {
            if (s.role == role) {
                return s;
            }
        }
        return null;
    }

    /** Сравнение реквизитов двух снимков (kind+тип+значение по трём ключам). */
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
