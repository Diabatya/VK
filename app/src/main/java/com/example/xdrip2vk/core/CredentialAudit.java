package com.example.xdrip2vk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ядро проверки сохранённых реквизитов трёх источников (задание 2Г.3А).
 * Чистая Java без Android: получает неизменяемые снимки encrypted
 * (secure_tokens), plain (secure_tokens_plain) и default (legacy-ключи
 * общих настроек). Не открывает файлы, не обращается к Keystore, не пишет
 * настройки, не удаляет ключи и не делает сетевых запросов.
 *
 * Право использовать токен для отправки даёт только {@link Verdict#READY_SECURED}.
 * MIGRATION_REQUIRED возвращает план для будущего исполнителя — данные плана
 * НЕ являются разрешённым активным токеном.
 *
 * Секреты не попадают в toString/диагностику: печатаются только
 * состояния и счётчики.
 */
public final class CredentialAudit {

    private CredentialAudit() {
    }

    /** Состояние чтения источника. */
    public enum Read {
        READABLE, UNAVAILABLE, CORRUPT
    }

    /** Роль источника в модели хранения. */
    public enum Role {
        ENCRYPTED, PLAIN, DEFAULT
    }

    /** Вердикт проверки. */
    public enum Verdict {
        /** Реквизитов нет. */
        EMPTY,
        /** Однозначные реквизиты только в доступном encrypted — разрешение. */
        READY_SECURED,
        /** Однозначные legacy-данные/одинаковые копии — нужен перенос/очистка. */
        MIGRATION_REQUIRED,
        /** Значения расходятся или происхождение неоднозначно. */
        CONFLICT,
        /** Необходимый источник недоступен — достоверного решения нет. */
        UNAVAILABLE,
        /** Повреждённые данные — подстановка другого источника запрещена. */
        CORRUPT
    }

    /** Фиксированные коды причин — без исходных значений. */
    public enum Reason {
        NONE,
        /** Ни один источник не содержит ключей реквизитов. */
        NO_CREDENTIALS,
        /** Токен+адресаты только в encrypted. */
        UNIQUE_SECURED,
        /** Данные только в plain/default — перенос в encrypted. */
        LEGACY_ONLY,
        /** Одинаковые реквизиты в нескольких источниках — дедупликация. */
        DUPLICATE_COPIES,
        /** Адресаты без токена — очистка осиротевших ключей. */
        ORPHAN_ADDRESSES,
        /** Encrypted недоступен — legacy не выбирается для отправки. */
        ENCRYPTED_UNAVAILABLE,
        /** Plain/default недоступен — однозначность не доказана. */
        SOURCE_UNAVAILABLE,
        /** Источник повреждён (чтение или тип значения). */
        SOURCE_CORRUPT,
        /** Разные непустые токены. */
        TOKEN_MISMATCH,
        /** Разные явно сохранённые адресаты при равном токене. */
        ADDRESS_MISMATCH,
        /** Пустой сохранённый токен рядом с непустым — удаление не доказано. */
        EMPTY_TOKEN_AMBIGUITY,
        /** Токен в одном источнике, адресаты без токена — в другом. */
        SPLIT_CREDENTIALS
    }

    /** Одно значение ключа в снимке источника. */
    public static final class Entry {
        public enum Kind {
            /** Ключа нет. */
            ABSENT,
            /** Ключ есть, значение — пустая строка (только для токена). */
            EMPTY,
            /** Ключ есть, значение присутствует. */
            PRESENT,
            /** Ключ есть, тип значения неожиданный — источник нечитаем. */
            WRONG_TYPE
        }

        public final Kind kind;
        /** Строковое значение (токен) — только при PRESENT. */
        public final String string;
        /** Числовое значение (адресат) — только при PRESENT. */
        public final long number;

        private Entry(Kind kind, String s, long n) {
            this.kind = kind;
            this.string = s;
            this.number = n;
        }

        public static Entry absent() {
            return new Entry(Kind.ABSENT, "", 0);
        }

        public static Entry string(String v) {
            String s = v == null ? "" : v.trim(); // нормализация — прежний trim
            if (s.isEmpty()) {
                return new Entry(Kind.EMPTY, "", 0);
            }
            return new Entry(Kind.PRESENT, s, 0);
        }

        public static Entry number(long v) {
            return new Entry(Kind.PRESENT, "", v);
        }

        public static Entry wrongType() {
            return new Entry(Kind.WRONG_TYPE, "", 0);
        }

        @Override
        public String toString() {
            return "Entry{" + kind + "}";
        }
    }

    /** Неизменяемый снимок одного источника. */
    public static final class Source {
        public final Role role;
        public final Read read;
        public final Entry token;
        public final Entry chatId;
        public final Entry alertChatId;

        private Source(Role role, Read read, Entry token, Entry chatId,
                       Entry alertChatId) {
            this.role = role;
            this.read = read;
            this.token = token;
            this.chatId = chatId;
            this.alertChatId = alertChatId;
        }

        public static Source of(Role role, Read read, Entry token,
                                Entry chatId, Entry alertChatId) {
            return new Source(role, read,
                    token == null ? Entry.absent() : token,
                    chatId == null ? Entry.absent() : chatId,
                    alertChatId == null ? Entry.absent() : alertChatId);
        }

        public static Source unavailable(Role role) {
            return new Source(role, Read.UNAVAILABLE,
                    Entry.absent(), Entry.absent(), Entry.absent());
        }

        public static Source corrupt(Role role) {
            return new Source(role, Read.CORRUPT,
                    Entry.absent(), Entry.absent(), Entry.absent());
        }

        boolean hasKey() {
            return token.kind != Entry.Kind.ABSENT
                    || chatId.kind != Entry.Kind.ABSENT
                    || alertChatId.kind != Entry.Kind.ABSENT;
        }

        boolean hasWrongType() {
            return token.kind == Entry.Kind.WRONG_TYPE
                    || chatId.kind == Entry.Kind.WRONG_TYPE
                    || alertChatId.kind == Entry.Kind.WRONG_TYPE;
        }

        boolean hasAddressWithoutToken() {
            // ABSENT и EMPTY токен — «адресат есть, токена нет».
            return token.kind != Entry.Kind.PRESENT
                    && (chatId.kind != Entry.Kind.ABSENT
                            || alertChatId.kind != Entry.Kind.ABSENT);
        }

        /** Эффективный адресат: отсутствующий ключ и явный 0 равнозначны. */
        long effectiveChatId() {
            return chatId.kind == Entry.Kind.PRESENT ? chatId.number : 0;
        }

        long effectiveAlertChatId() {
            return alertChatId.kind == Entry.Kind.PRESENT
                    ? alertChatId.number : 0;
        }

        @Override
        public String toString() {
            return "Source{" + role + "," + read + ",token=" + token.kind
                    + ",chat=" + chatId.kind + ",alert=" + alertChatId.kind
                    + "}";
        }
    }

    /** План переноса для будущего исполнителя. Данные НЕ активный токен. */
    public static final class Plan {
        public final String token;
        public final long chatId;
        public final long alertChatId;
        /** Роли источников, подлежащих очистке после подтверждённой записи. */
        public final List<Role> clearSources;
        /** true — запись в encrypted не требуется (данные уже там). */
        public final boolean alreadyEncrypted;

        private Plan(String token, long chatId, long alertChatId,
                     List<Role> clearSources, boolean alreadyEncrypted) {
            this.token = token;
            this.chatId = chatId;
            this.alertChatId = alertChatId;
            this.clearSources =
                    java.util.Collections.unmodifiableList(clearSources);
            this.alreadyEncrypted = alreadyEncrypted;
        }

        @Override
        public String toString() {
            return "Plan{clear=" + clearSources
                    + ",alreadyEncrypted=" + alreadyEncrypted + "}";
        }
    }

    /** Результат проверки: вердикт + код причины + необязательный план. */
    public static final class Outcome {
        public final Verdict verdict;
        public final Reason reason;
        public final Plan plan;

        private Outcome(Verdict verdict, Reason reason, Plan plan) {
            this.verdict = verdict;
            this.reason = reason;
            this.plan = plan;
        }

        /** Право использовать токен для отправки. */
        public boolean sendPermitted() {
            return verdict == Verdict.READY_SECURED;
        }

        @Override
        public String toString() {
            return "Outcome{" + verdict + "," + reason + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Outcome)) {
                return false;
            }
            Outcome x = (Outcome) o;
            return verdict == x.verdict && reason == x.reason;
        }

        @Override
        public int hashCode() {
            return Objects.hash(verdict, reason);
        }
    }

    /**
     * Проверка трёх снимков. Детерминирована: без приоритета обхода —
     * все комбинации сводятся к одной таблице решений.
     */
    public static Outcome check(Source enc, Source plain, Source def) {
        Source[] all = {enc, plain, def};

        // 1. Повреждение: источник с потенциальными реквизитами нельзя
        // подменять другим — никакой подстановки.
        for (Source s : all) {
            if (s.read == Read.CORRUPT || s.hasWrongType()) {
                return new Outcome(Verdict.CORRUPT, Reason.SOURCE_CORRUPT, null);
            }
        }

        // 2. Недоступность: encrypted обязателен для решения; недоступные
        // legacy источники не дают доказать однозначность — тоже UNAVAILABLE.
        if (enc.read == Read.UNAVAILABLE) {
            return new Outcome(Verdict.UNAVAILABLE,
                    Reason.ENCRYPTED_UNAVAILABLE, null);
        }
        if (plain.read == Read.UNAVAILABLE || def.read == Read.UNAVAILABLE) {
            return new Outcome(Verdict.UNAVAILABLE,
                    Reason.SOURCE_UNAVAILABLE, null);
        }

        // 3. Все READABLE — полная таблица по содержимому.
        List<Source> tokenHolders = new ArrayList<>();
        List<Source> emptyTokenHolders = new ArrayList<>();
        List<Source> orphanAddressHolders = new ArrayList<>();
        boolean anyKey = false;
        for (Source s : all) {
            if (s.hasKey()) {
                anyKey = true;
            }
            if (s.token.kind == Entry.Kind.PRESENT) {
                tokenHolders.add(s);
            } else if (s.token.kind == Entry.Kind.EMPTY) {
                emptyTokenHolders.add(s);
            }
            if (s.hasAddressWithoutToken()) {
                orphanAddressHolders.add(s);
            }
        }
        if (!anyKey) {
            return new Outcome(Verdict.EMPTY, Reason.NO_CREDENTIALS, null);
        }

        // Адресаты без токена при наличии хотя бы одного непустого токена —
        // смешивать нельзя, происхождение не доказано.
        if (!tokenHolders.isEmpty() && !orphanAddressHolders.isEmpty()) {
            return new Outcome(Verdict.CONFLICT, Reason.SPLIT_CREDENTIALS,
                    null);
        }

        if (tokenHolders.isEmpty()) {
            // Непустого токена нет нигде: реквизитов для отправки нет.
            // Осиротевшие адресаты/пустые записи — только очистка.
            if (!orphanAddressHolders.isEmpty()
                    || !emptyTokenHolders.isEmpty()) {
                if (!orphanAddressHolders.isEmpty()) {
                    List<Role> clear = new ArrayList<>();
                    for (Source s : orphanAddressHolders) {
                        clear.add(s.role);
                    }
                    for (Source s : emptyTokenHolders) {
                        clear.add(s.role);
                    }
                    return new Outcome(Verdict.MIGRATION_REQUIRED,
                            Reason.ORPHAN_ADDRESSES,
                            new Plan("", 0, 0, clear, false));
                }
                return new Outcome(Verdict.EMPTY, Reason.NO_CREDENTIALS, null);
            }
            return new Outcome(Verdict.EMPTY, Reason.NO_CREDENTIALS, null);
        }

        // Пустой сохранённый токен рядом с непустым: удаление не доказано.
        if (!emptyTokenHolders.isEmpty()) {
            return new Outcome(Verdict.CONFLICT,
                    Reason.EMPTY_TOKEN_AMBIGUITY, null);
        }

        // Разные непустые токены — блокирующий конфликт.
        String first = tokenHolders.get(0).token.string;
        for (Source s : tokenHolders) {
            if (!s.token.string.equals(first)) {
                return new Outcome(Verdict.CONFLICT, Reason.TOKEN_MISMATCH,
                        null);
            }
        }

        // Один токен в нескольких источниках: адресаты должны совпадать
        // (absent ≡ явный 0 — оба «адресат не выбран»; любая другая
        // разница — конфликт).
        Source base = tokenHolders.get(0);
        for (Source s : tokenHolders) {
            if (s.effectiveChatId() != base.effectiveChatId()
                    || s.effectiveAlertChatId() != base.effectiveAlertChatId()) {
                return new Outcome(Verdict.CONFLICT, Reason.ADDRESS_MISMATCH,
                        null);
            }
        }

        if (tokenHolders.size() == 1) {
            Source only = tokenHolders.get(0);
            if (only.role == Role.ENCRYPTED) {
                return new Outcome(Verdict.READY_SECURED,
                        Reason.UNIQUE_SECURED, null);
            }
            // Однозначные legacy-данные — перенос в encrypted + очистка.
            List<Role> clear = new ArrayList<>();
            clear.add(only.role);
            return new Outcome(Verdict.MIGRATION_REQUIRED, Reason.LEGACY_ONLY,
                    new Plan(first, only.effectiveChatId(),
                            only.effectiveAlertChatId(), clear, false));
        }

        // Одинаковые реквизиты в нескольких источниках — дедупликация после
        // будущей подтверждённой записи; если encrypted среди держателей —
        // писать не нужно, только очистить остальные.
        boolean encHolder = false;
        List<Role> clear = new ArrayList<>();
        for (Source s : tokenHolders) {
            if (s.role == Role.ENCRYPTED) {
                encHolder = true;
            } else {
                clear.add(s.role);
            }
        }
        return new Outcome(Verdict.MIGRATION_REQUIRED,
                Reason.DUPLICATE_COPIES,
                new Plan(first, base.effectiveChatId(),
                        base.effectiveAlertChatId(), clear, encHolder));
    }
}
