package com.example.xdrip2vk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ядро проверки сохранённых реквизитов трёх источников (задание 2Г.3А.1).
 * Чистая Java без Android: получает неизменяемые снимки encrypted
 * (secure_tokens), plain (secure_tokens_plain) и default (legacy-ключи
 * общих настроек). Не открывает файлы, не обращается к Keystore, не пишет
 * настройки, не удаляет ключи и не делает сетевых запросов.
 *
 * Источник определяется только его {@link Role}: check принимает три
 * снимка в любом порядке и требует ровно по одному ENCRYPTED, PLAIN и
 * DEFAULT — иначе блокирующий INVALID_INPUT.
 *
 * Тип значения фиксирован записью: токен допускает только строку или
 * отсутствие, адресаты — только long или отсутствие. Числовой токен,
 * строковый (в т.ч. пустой) адресат и WRONG_TYPE → CORRUPT.
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

    /** Роль источника в модели хранения — единственное основание выбора. */
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
        /** Значения расходятся или происхождение/структура неоднозначны. */
        CONFLICT,
        /** Необходимый источник недоступен — достоверного решения нет. */
        UNAVAILABLE,
        /** Повреждённые данные — подстановка другого источника запрещена. */
        CORRUPT
    }

    /** Фиксированные коды причин — без исходных значений. */
    public enum Reason {
        NONE,
        /** Нарушена структура входа: null или дубль/пропуск роли. */
        INVALID_INPUT,
        /** Ни один источник не содержит ключей реквизитов. */
        NO_CREDENTIALS,
        /** Токен+адресаты только в encrypted. */
        UNIQUE_SECURED,
        /** Данные только в plain/default — перенос в encrypted. */
        LEGACY_ONLY,
        /** Одинаковые реквизиты в нескольких источниках — дедупликация. */
        DUPLICATE_COPIES,
        /** Ключи-остатки без непустого токена — очистка. */
        ORPHAN_ADDRESSES,
        /** Encrypted недоступен — legacy не выбирается для отправки. */
        ENCRYPTED_UNAVAILABLE,
        /** Plain/default недоступен — однозначность не доказана. */
        SOURCE_UNAVAILABLE,
        /** Источник повреждён (чтение или тип значения поля). */
        SOURCE_CORRUPT,
        /** Разные непустые токены. */
        TOKEN_MISMATCH,
        /** Разные явно сохранённые адресаты при равном токене. */
        ADDRESS_MISMATCH,
        /** Пустой сохранённый токен рядом с непустым — удаление не доказано. */
        EMPTY_TOKEN_AMBIGUITY,
        /** Токен в одном источнике, адресаты без токена — в другом. */
        SPLIT_CREDENTIALS,
        /** Назначение не совпадает с ожидаемыми реквизитами операции. */
        DESTINATION_MISMATCH,
        /** Служебное состояние присутствует, но значение/версия неизвестны. */
        SERVICE_STATE_INVALID,
        /** Подтверждённое удаление реквизитов пользователем. */
        DELETED_BY_USER,
        /**
         * Маркер изменился посторонней операцией во время незавершённой
         * операции (в т.ч. появилось новое удаление) — старый план его
         * не снимает и не перезаписывает.
         */
        SERVICE_STATE_CHANGED,
        /**
         * Маркер «удалено» не согласуется с реквизитами источников
         * (креденшелы в encrypted/legacy при удалённом состоянии) —
         * несогласованное состояние, автоматически не исправляется.
         */
        SERVICE_STATE_MISMATCH,
        /**
         * Ожидаемая версия токена (fingerprint) не совпадает с
         * актуальной — данные, с которыми работал вызывающий, устарели;
         * запись не выполняется.
         */
        STALE_TOKEN
    }

    /** Одно значение ключа в снимке источника. */
    public static final class Entry {
        /** Тип записи значения — отдельно от значения и от Kind. */
        public enum Type {
            NONE, STRING, NUMBER
        }

        public enum Kind {
            /** Ключа нет. */
            ABSENT,
            /** Ключ есть, значение — пустая строка (допустимо только у токена). */
            EMPTY,
            /** Ключ есть, значение присутствует. */
            PRESENT,
            /** Ключ есть, тип значения неожиданный — источник нечитаем. */
            WRONG_TYPE
        }

        public final Kind kind;
        /** Заявленный тип значения; тип не выводится из string/number. */
        public final Type type;
        /** Строковое значение — только при type=STRING. */
        public final String string;
        /** Числовое значение — только при type=NUMBER. */
        public final long number;

        private Entry(Kind kind, Type type, String s, long n) {
            this.kind = kind;
            this.type = type;
            this.string = s;
            this.number = n;
        }

        public static Entry absent() {
            return new Entry(Kind.ABSENT, Type.NONE, "", 0);
        }

        public static Entry string(String v) {
            String s = v == null ? "" : v.trim(); // нормализация — прежний trim
            if (s.isEmpty()) {
                return new Entry(Kind.EMPTY, Type.STRING, "", 0);
            }
            return new Entry(Kind.PRESENT, Type.STRING, s, 0);
        }

        public static Entry number(long v) {
            return new Entry(Kind.PRESENT, Type.NUMBER, "", v);
        }

        public static Entry wrongType() {
            return new Entry(Kind.WRONG_TYPE, Type.NONE, "", 0);
        }

        @Override
        public String toString() {
            return "Entry{" + kind + "," + type + "}";
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

        public boolean hasKey() {
            return token.kind != Entry.Kind.ABSENT
                    || chatId.kind != Entry.Kind.ABSENT
                    || alertChatId.kind != Entry.Kind.ABSENT;
        }

        /** Токен: только строка или отсутствие. */
        private static boolean badToken(Entry e) {
            return e.kind == Entry.Kind.WRONG_TYPE
                    || (e.kind != Entry.Kind.ABSENT
                            && e.type != Entry.Type.STRING);
        }

        /** Адресат: только long или отсутствие. Пустая строка — повреждение. */
        private static boolean badAddress(Entry e) {
            return e.kind == Entry.Kind.WRONG_TYPE
                    || (e.kind != Entry.Kind.ABSENT
                            && e.type != Entry.Type.NUMBER);
        }

        public boolean hasFieldTypeMismatch() {
            return badToken(token) || badAddress(chatId)
                    || badAddress(alertChatId);
        }

        boolean hasAddressWithoutToken() {
            // ABSENT и EMPTY токен — «адресат есть, токена нет».
            return token.kind != Entry.Kind.PRESENT
                    && (chatId.kind != Entry.Kind.ABSENT
                            || alertChatId.kind != Entry.Kind.ABSENT);
        }

        /** Эффективный адресат: отсутствующий ключ и явный 0 равнозначны. */
        public long effectiveChatId() {
            return chatId.kind == Entry.Kind.PRESENT ? chatId.number : 0;
        }

        public long effectiveAlertChatId() {
            return alertChatId.kind == Entry.Kind.PRESENT
                    ? alertChatId.number : 0;
        }

        @Override
        public String toString() {
            return "Source{" + role + "," + read + ",token=" + token.kind
                    + "/" + token.type + ",chat=" + chatId.kind + "/"
                    + chatId.type + ",alert=" + alertChatId.kind + "/"
                    + alertChatId.type + "}";
        }
    }

    /** План переноса для будущего исполнителя. Данные НЕ активный токен. */
    public static final class Plan {
        public final String token;
        public final long chatId;
        public final long alertChatId;
        /**
         * Роли источников, подлежащих очистке после подтверждённой записи.
         * Без повторов, стабильный порядок ролей: ENCRYPTED, PLAIN, DEFAULT.
         */
        public final List<Role> clearSources;
        /** true — запись в encrypted не требуется (данные уже там). */
        public final boolean alreadyEncrypted;

        public static Plan of(String token, long chatId, long alertChatId,
                              List<Role> clearSources,
                              boolean alreadyEncrypted) {
            return new Plan(token, chatId, alertChatId, clearSources,
                    alreadyEncrypted);
        }

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
     * Проверка трёх снимков в любом порядке. Сначала структура входа и
     * разрешение источников по Role, затем содержательная проверка.
     */
    public static Outcome check(Source s1, Source s2, Source s3) {
        Source enc = null, plain = null, def = null;
        for (Source s : new Source[]{s1, s2, s3}) {
            if (s == null || s.role == null || s.read == null) {
                return new Outcome(Verdict.CONFLICT, Reason.INVALID_INPUT,
                        null);
            }
            switch (s.role) {
                case ENCRYPTED:
                    if (enc != null) {
                        return invalid();
                    }
                    enc = s;
                    break;
                case PLAIN:
                    if (plain != null) {
                        return invalid();
                    }
                    plain = s;
                    break;
                case DEFAULT:
                    if (def != null) {
                        return invalid();
                    }
                    def = s;
                    break;
                default:
                    return invalid();
            }
        }
        if (enc == null || plain == null || def == null) {
            return invalid();
        }

        // Канонический порядок обхода — по ролям, не по позиции аргумента.
        Source[] all = {enc, plain, def};

        // 1. Повреждение: источник с потенциальными реквизитами нельзя
        // подменять другим — никакой подстановки.
        for (Source s : all) {
            if (s.read == Read.CORRUPT || s.hasFieldTypeMismatch()) {
                return new Outcome(Verdict.CORRUPT, Reason.SOURCE_CORRUPT,
                        null);
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
        boolean anyKey = false;
        boolean hasOrphanAddresses = false;
        for (Source s : all) {
            if (s.hasKey()) {
                anyKey = true;
            }
            if (s.token.kind == Entry.Kind.PRESENT) {
                tokenHolders.add(s);
            } else if (s.hasAddressWithoutToken()) {
                hasOrphanAddresses = true;
            }
        }
        if (!anyKey) {
            return new Outcome(Verdict.EMPTY, Reason.NO_CREDENTIALS, null);
        }

        // Адресаты без токена при наличии хотя бы одного непустого токена —
        // смешивать нельзя, происхождение не доказано.
        if (!tokenHolders.isEmpty() && hasOrphanAddresses) {
            return new Outcome(Verdict.CONFLICT, Reason.SPLIT_CREDENTIALS,
                    null);
        }

        if (tokenHolders.isEmpty()) {
            // Непустого токена нет нигде: осиротевшие адресаты и пустые
            // записи токена — остатки на очистку, не реквизиты.
            List<Role> clear = new ArrayList<>();
            for (Source s : all) { // канонический порядок, без повторов
                if (s.hasKey()) {
                    clear.add(s.role);
                }
            }
            return clear.isEmpty()
                    ? new Outcome(Verdict.EMPTY, Reason.NO_CREDENTIALS, null)
                    : new Outcome(Verdict.MIGRATION_REQUIRED,
                            Reason.ORPHAN_ADDRESSES,
                            new Plan("", 0, 0, clear, false));
        }

        // Пустой сохранённый токен рядом с непустым: удаление не доказано.
        for (Source s : all) {
            if (s.token.kind == Entry.Kind.EMPTY) {
                return new Outcome(Verdict.CONFLICT,
                        Reason.EMPTY_TOKEN_AMBIGUITY, null);
            }
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
        for (Source s : all) { // канонический порядок ролей
            if (s.token.kind == Entry.Kind.PRESENT) {
                if (s.role == Role.ENCRYPTED) {
                    encHolder = true;
                } else {
                    clear.add(s.role);
                }
            }
        }
        return new Outcome(Verdict.MIGRATION_REQUIRED,
                Reason.DUPLICATE_COPIES,
                new Plan(first, base.effectiveChatId(),
                        base.effectiveAlertChatId(), clear, encHolder));
    }

    private static Outcome invalid() {
        return new Outcome(Verdict.CONFLICT, Reason.INVALID_INPUT, null);
    }
}
