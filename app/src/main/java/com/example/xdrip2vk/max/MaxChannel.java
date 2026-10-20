package com.example.xdrip2vk.max;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;

import com.example.xdrip2vk.Logger;

import java.util.function.Function;

/**
 * Канал MAX: подтверждённое персистентное состояние и настройки. Живёт в
 * отдельном файле SharedPreferences "max_channel" — не смешивается с
 * реквизитами VK (secure_tokens) и с очередью delivery_state_v2. Файл
 * попадает под общие исключения backup (домен sharedpref исключён целиком).
 *
 * Модель подтверждения (MAX-1Б):
 *  - Чтения для решений идут из опубликованного снимка confirmed, а не из
 *    сырых prefs: снимок обновляется только после commit()==true.
 *  - commit()==false или исключение — запись не состоялась; кеш хранилища
 *    при этом мог «запачкаться», но снимок не меняется, новый handle того же
 *    диска отказ не обходит.
 *  - Каждый подтверждённый выбор чата получает поколение (generation);
 *    pending привязан к поколению цели — смена цели и удаление старого
 *    pending подтверждаются одной записью.
 *  - Перед внешней командой пишется зарезервированная попытка; после
 *    восстановления RESERVED-без-результата автоматически не повторяется.
 */
public final class MaxChannel {

    /** Пакет приложения MAX — единственный источник уведомлений-кандидатов. */
    public static final String MAX_PACKAGE = "ru.oneme.app";

    /** Ключи в default SharedPreferences (общий экран настроек). */
    public static final String PREF_VK_ENABLED = "send_vk_enabled";
    public static final String PREF_MAX_ENABLED = "send_max_enabled";

    /** Отложенное показание старше этого уже не отправляем — придёт следующее. */
    public static final long PENDING_MAX_AGE_MS = 15 * 60_000L;

    private static final String FILE = "max_channel";

    private static final String KEY_TARGET_NAME = "target_name";
    private static final String KEY_TARGET_CHANNEL = "target_channel";
    private static final String KEY_TARGET_NOTIF = "target_notif_id";
    private static final String KEY_TARGET_GEN = "target_gen";
    private static final String KEY_NEXT_GEN = "next_gen";
    private static final String KEY_BLOCKED_BY = "blocked_by";
    private static final String KEY_BLOCKED_REASON = "blocked_reason";
    /** Конфликт: у другого чата та же системная операция ответа. */
    public static final String BLOCKED_CONFLICT = "conflict";
    /** Неоднозначность: имя совпадает, но идентификатор чата не подтверждён. */
    public static final String BLOCKED_AMBIGUOUS = "ambiguous";
    private static final String KEY_PENDING_TEXT = "pending_text";
    private static final String KEY_PENDING_TS = "pending_reading_ts";
    private static final String KEY_PENDING_GEN = "pending_gen";
    private static final String KEY_HANDOFF_TS = "last_handoff_ts";
    private static final String KEY_HANDOFF_READING_TS = "last_handoff_reading_ts";
    private static final String KEY_TEST_HANDOFF_TS = "last_test_handoff_ts";
    private static final String KEY_ATTEMPT_ID = "attempt_id";
    private static final String KEY_ATTEMPT_KIND = "attempt_kind";
    private static final String KEY_ATTEMPT_GEN = "attempt_gen";
    private static final String KEY_ATTEMPT_CHAT = "attempt_chat";
    private static final String KEY_ATTEMPT_CHANNEL = "attempt_channel";
    private static final String KEY_ATTEMPT_NOTIF = "attempt_notif_id";
    private static final String KEY_ATTEMPT_READING_TS = "attempt_reading_ts";
    private static final String KEY_ATTEMPT_STATE = "attempt_state";
    // Попытка тестового сообщения хранится отдельной записью (ключи
    // "test_attempt_*") — независимое доказательство: запись/итог теста не
    // перезаписывает попытку показания и не отменяет запрет его повтора.

    /**
     * Ограниченные автоматические повторы неподтверждённой записи: число
     * попыток ограничено, интервал нарастает (BASE * номер попытки).
     */
    static final int MAX_WRITE_ATTEMPTS = 3;
    static final long RETRY_BASE_MS = 250;

    private static final String TAG = "MaxChannel";

    /** Идентификатор уведомления не сохранён — адресат не подтверждён. */
    public static final int NOTIF_ID_UNKNOWN = -1;

    /** Подмена хранилища и планировщика повторов — только для тестов. */
    public static volatile Function<Context, MaxStore> storeFactoryForTest;
    public static volatile RetryScheduler retrySchedulerForTest;

    /** Отложенный повтор неподтверждённой записи. */
    public interface RetryScheduler {
        void schedule(Runnable task, long delayMs);
    }

    private MaxChannel() { }

    // --- Снимок подтверждённого состояния ---------------------------------------

    /**
     * Идентичность выбранного чата: имя + канал + id уведомления + поколение.
     * Поколение выдаётся каждому подтверждённому выбору и связывает pending
     * и попытки с конкретным выбором адресата.
     */
    public static final class Target {
        public final String chatName;
        public final String channelId;
        public final int notificationId;
        public final long generation;

        Target(String chatName, String channelId, int notificationId, long generation) {
            this.chatName = chatName;
            this.channelId = channelId;
            this.notificationId = notificationId;
            this.generation = generation;
        }

        boolean sameIdentity(Target other) {
            return other != null
                    && chatName.equals(other.chatName)
                    && channelId.equals(other.channelId)
                    && notificationId == other.notificationId;
        }
    }

    /** Одно ожидающее показание, привязанное к поколению выбранной цели. */
    public static final class Pending {
        public final String text;
        public final long readingTs;
        public final long targetGeneration;

        Pending(String text, long readingTs, long targetGeneration) {
            this.text = text;
            this.readingTs = readingTs;
            this.targetGeneration = targetGeneration;
        }
    }

    /**
     * Запись о внешней команде: резервируется ДО PendingIntent.send.
     * RESERVED без результата после восстановления = исход неизвестен —
     * автоматический повтор запрещён.
     */
    public static final class Attempt {
        public static final int STATE_RESERVED = 0;
        public static final int STATE_HANDED = 1;
        public static final int STATE_FAILED = 2;
        public static final int STATE_UNKNOWN = 3;

        public static final String KIND_READING = "reading";
        public static final String KIND_TEST = "test";

        public final long id;
        public final String kind;
        public final long targetGeneration;
        public final String chatName;
        public final String channelId;
        public final int notificationId;
        public final long readingTs;
        public final int state;

        Attempt(long id, String kind, long targetGeneration, Target target,
                long readingTs, int state) {
            this.id = id;
            this.kind = kind;
            this.targetGeneration = targetGeneration;
            this.chatName = target.chatName;
            this.channelId = target.channelId;
            this.notificationId = target.notificationId;
            this.readingTs = readingTs;
            this.state = state;
        }

        Attempt withState(int newState) {
            return new Attempt(id, kind, targetGeneration,
                    new Target(chatName, channelId, notificationId, targetGeneration),
                    readingTs, newState);
        }

        /** Попытка относится к этому ожидающему показанию. */
        boolean covers(Pending p, String kind) {
            return p != null && this.kind.equals(kind)
                    && this.targetGeneration == p.targetGeneration
                    && this.readingTs == p.readingTs;
        }
    }

    /** Опубликованный снимок — единственный источник для решений и UI. */
    static final class Snapshot {
        final Target target;
        final Pending pending;
        final String blockedBy;
        final String blockedReason;
        final Attempt attempt;
        final Attempt testAttempt;
        final long nextGen;
        final long lastHandoffMs;
        final long lastHandoffReadingTs;
        final long lastTestHandoffMs;

        Snapshot(Target target, Pending pending, String blockedBy, String blockedReason,
                 Attempt attempt, Attempt testAttempt, long nextGen, long lastHandoffMs,
                 long lastHandoffReadingTs, long lastTestHandoffMs) {
            this.target = target;
            this.pending = pending;
            this.blockedBy = blockedBy;
            this.blockedReason = blockedReason;
            this.attempt = attempt;
            this.testAttempt = testAttempt;
            this.nextGen = nextGen;
            this.lastHandoffMs = lastHandoffMs;
            this.lastHandoffReadingTs = lastHandoffReadingTs;
            this.lastTestHandoffMs = lastTestHandoffMs;
        }
    }

    private static volatile Snapshot confirmed;
    private static volatile MaxStore cachedStore;

    static MaxStore store(Context app) {
        Function<Context, MaxStore> f = storeFactoryForTest;
        if (f != null) {
            MaxStore s = f.apply(app);
            if (s != null) return s;
        }
        MaxStore s = cachedStore;
        if (s == null) {
            s = new MaxStore.PrefsStore(
                    app.getSharedPreferences(FILE, Context.MODE_PRIVATE));
            cachedStore = s;
        }
        return s;
    }

    /** Снимок: загружается из хранилища один раз и меняется только подтверждёнными записями. */
    static Snapshot state(Context app) {
        Snapshot s = confirmed;
        if (s == null) {
            synchronized (MaxChannel.class) {
                s = confirmed;
                if (s == null) {
                    if (MaxState.storageError) {
                        // Кеш хранилища мог «запачкаться» неподтверждённой
                        // записью — в этом процессе снимку доверять нельзя;
                        // новый handle запрет не обходит. После настоящего
                        // перезапуска процесса флаг снят и загрузка идёт
                        // по подтверждённому диску.
                        s = new Snapshot(null, null, null, null, null, null,
                                0, 0, -1, 0);
                        Logger.w(TAG, "снимок не восстановлен — в кеше неподтверждённые данные");
                    } else {
                        s = load(store(app));
                    }
                    confirmed = s;
                    if (s.attempt != null && s.attempt.state == Attempt.STATE_RESERVED) {
                        // Команда резервировалась, результата нет — процесс мог
                        // умереть после фактической передачи. Повтор запрещён.
                        MaxState.outcomeUnknown = true;
                        Logger.w(TAG, "обнаружена зарезервированная попытка без результата — исход неизвестен");
                    }
                }
            }
        }
        return s;
    }

    private static Snapshot load(MaxStore s) {
        String name = s.getString(KEY_TARGET_NAME, null);
        Target target = name == null ? null : new Target(
                name,
                s.getString(KEY_TARGET_CHANNEL, ""),
                s.getInt(KEY_TARGET_NOTIF, NOTIF_ID_UNKNOWN),
                s.getLong(KEY_TARGET_GEN, 0));
        String pText = s.getString(KEY_PENDING_TEXT, null);
        Pending pending = pText == null ? null : new Pending(
                pText,
                s.getLong(KEY_PENDING_TS, 0),
                s.getLong(KEY_PENDING_GEN, -1));
        Attempt attempt = loadAttempt(s, false);
        Attempt testAttempt = loadAttempt(s, true);
        return new Snapshot(target, pending,
                s.getString(KEY_BLOCKED_BY, null),
                s.getString(KEY_BLOCKED_REASON, null),
                attempt, testAttempt,
                s.getLong(KEY_NEXT_GEN, 0),
                s.getLong(KEY_HANDOFF_TS, 0),
                s.getLong(KEY_HANDOFF_READING_TS, -1),
                s.getLong(KEY_TEST_HANDOFF_TS, 0));
    }

    private static Attempt loadAttempt(MaxStore s, boolean test) {
        String p = test ? "test_attempt_" : "attempt_";
        long id = s.getLong(p + "id", 0);
        if (id == 0) return null;
        long gen = s.getLong(p + "gen", -1);
        return new Attempt(id,
                s.getString(p + "kind", test ? Attempt.KIND_TEST : Attempt.KIND_READING),
                gen,
                new Target(s.getString(p + "chat", ""),
                        s.getString(p + "channel", ""),
                        s.getInt(p + "notif_id", NOTIF_ID_UNKNOWN), gen),
                s.getLong(p + "reading_ts", 0),
                s.getInt(p + "state", Attempt.STATE_UNKNOWN));
    }

    // --- Подтверждённые записи ---------------------------------------------------

    /** Одна подтверждаемая запись: правки на диск + обновление снимка при успехе. */
    interface Mutation {
        /** store доступен для условных чтений текущего дискового состояния. */
        void apply(MaxStore store, MaxStore.Editor e, Snapshot current);

        /** Новый снимок при подтверждённой записи. */
        Snapshot evolve(Snapshot current);

        /**
         * Применимость по явной версии состояния (поколение цели, время
         * показания, id попытки, заблокировавший чат). Отложенный повтор
         * выполняется только если состояние не ушло вперёд: устаревший повтор
         * отменяется, а не применяется к чужому состоянию.
         */
        default boolean appliesTo(Snapshot current) {
            return true;
        }
    }

    /**
     * Подтверждённая запись на рабочем потоке. commit()==true — снимок
     * обновляется и возвращается true. Иначе — storageError, ограниченный
     * автоматический повтор того же commit и false.
     */
    static boolean commitState(Context app, Mutation m) {
        return commitAttempt(app, m, 1);
    }

    private static boolean commitAttempt(Context app, Mutation m, int attemptNo) {
        Snapshot cur = state(app);
        if (!m.appliesTo(cur)) {
            // Мир ушёл вперёд (новая цель/показание/попытка): применять эту
            // мутацию поздно — повтор отменён, это не отказ хранилища.
            Logger.w(TAG, "запись отменена как устаревшая — состояние изменилось");
            return false;
        }
        MaxStore s = store(app);
        MaxStore.Editor e = s.edit();
        boolean ok;
        try {
            m.apply(s, e, cur);
            ok = e.commit();
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            synchronized (MaxSender.class) {
                confirmed = m.evolve(confirmed);
            }
            MaxState.storageError = false;
            return true;
        }
        // Запись не подтверждена — успехом не считается; повтор через
        // нарастающий интервал проверит применимость к актуальному снимку.
        MaxState.storageError = true;
        if (attemptNo < MAX_WRITE_ATTEMPTS) {
            retryScheduler().schedule(() -> commitAttempt(app, m, attemptNo + 1),
                    RETRY_BASE_MS * attemptNo);
        }
        Logger.w(TAG, "запись состояния не подтверждена (commit=false)");
        return false;
    }

    private static RetryScheduler retryScheduler() {
        RetryScheduler r = retrySchedulerForTest;
        if (r != null) return r;
        return MaxSender::scheduleWorker;
    }

    // --- Мутации -------------------------------------------------------------------

    /**
     * Подтверждённый выбор нового чата: поколение выделяется внутри записи
     * (nextGen+1), сброс pending/блокировки/попытки — той же записью. Повтор
     * применим, только если поколения не выделялись после нас: подтверждённый
     * выбор другого чата делает отложенный повтор устаревшим.
     */
    static boolean confirmTarget(Context app, String chatName, String channelId,
                                 int notificationId) {
        final long baseNextGen = state(app).nextGen;
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                return cur.nextGen == baseNextGen;
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                long gen = cur.nextGen + 1;
                e.putString(KEY_TARGET_NAME, chatName)
                        .putString(KEY_TARGET_CHANNEL, channelId)
                        .putInt(KEY_TARGET_NOTIF, notificationId)
                        .putLong(KEY_TARGET_GEN, gen)
                        .putLong(KEY_NEXT_GEN, gen)
                        .remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS).remove(KEY_PENDING_GEN)
                        .remove(KEY_BLOCKED_BY).remove(KEY_BLOCKED_REASON)
                        .remove(KEY_ATTEMPT_ID).remove(KEY_ATTEMPT_STATE);
            }

            @Override public Snapshot evolve(Snapshot c) {
                long gen = c.nextGen + 1;
                Target target = new Target(chatName, channelId, notificationId, gen);
                return new Snapshot(target, null, null, null, null, c.testAttempt,
                        gen, c.lastHandoffMs, c.lastHandoffReadingTs, c.lastTestHandoffMs);
            }
        });
    }

    static boolean confirmPending(Context app, String text, long readingTs, long gen) {
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                // Не заменять более новое показание, не писать pending под
                // уже сменившуюся цель и не воскрешать показание, более новое
                // из которых уже передано.
                if (cur.target == null || cur.target.generation != gen) return false;
                if (readingTs <= cur.lastHandoffReadingTs) return false;
                return cur.pending == null || cur.pending.readingTs <= readingTs;
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.putString(KEY_PENDING_TEXT, text)
                        .putLong(KEY_PENDING_TS, readingTs)
                        .putLong(KEY_PENDING_GEN, gen);
            }

            @Override public Snapshot evolve(Snapshot c) {
                return new Snapshot(c.target, new Pending(text, readingTs, gen),
                        c.blockedBy, c.blockedReason, c.attempt, c.testAttempt, c.nextGen,
                        c.lastHandoffMs, c.lastHandoffReadingTs, c.lastTestHandoffMs);
            }
        });
    }

    /** Удаление КОНКРЕТНОГО pending: повтор не трогает более новое показание. */
    static boolean confirmClearPending(Context app, Pending item) {
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                return cur.pending == null || cur.pending.readingTs == item.readingTs;
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS).remove(KEY_PENDING_GEN);
            }

            @Override public Snapshot evolve(Snapshot c) {
                Pending pending = c.pending;
                if (pending != null && pending.readingTs == item.readingTs) pending = null;
                return new Snapshot(c.target, pending, c.blockedBy, c.blockedReason,
                        c.attempt, c.testAttempt, c.nextGen,
                        c.lastHandoffMs, c.lastHandoffReadingTs, c.lastTestHandoffMs);
            }
        });
    }

    static boolean confirmBlocked(Context app, String reason, String chatName) {
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                // Не перезаписывать более свежую блокировку другого чата.
                return cur.blockedBy == null || cur.blockedBy.equals(chatName);
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.putString(KEY_BLOCKED_BY, chatName).putString(KEY_BLOCKED_REASON, reason);
            }

            @Override public Snapshot evolve(Snapshot c) {
                return new Snapshot(c.target, c.pending, chatName, reason, c.attempt,
                        c.testAttempt, c.nextGen, c.lastHandoffMs,
                        c.lastHandoffReadingTs, c.lastTestHandoffMs);
            }
        });
    }

    static boolean confirmClearBlocked(Context app) {
        final String expected = state(app).blockedBy;
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                // Снимаем только ту блокировку, которую видели: новую — нет.
                return expected == null ? cur.blockedBy == null
                        : expected.equals(cur.blockedBy);
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.remove(KEY_BLOCKED_BY).remove(KEY_BLOCKED_REASON);
            }

            @Override public Snapshot evolve(Snapshot c) {
                return new Snapshot(c.target, c.pending, null, null, c.attempt,
                        c.testAttempt, c.nextGen, c.lastHandoffMs,
                        c.lastHandoffReadingTs, c.lastTestHandoffMs);
            }
        });
    }

    /** Резерв попытки ДО внешней команды — без подтверждения команда не вызывается. */
    static boolean confirmReserveAttempt(Context app, Attempt attempt) {
        return commitState(app, attemptWrite(attempt));
    }

    private static String attemptPrefix(Attempt a) {
        return Attempt.KIND_TEST.equals(a.kind) ? "test_attempt_" : "attempt_";
    }

    private static Mutation attemptWrite(Attempt a) {
        final String p = attemptPrefix(a);
        return new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                // Не перезаписывать более новую попытку (результат старой не
                // меняет состояние следующей и не возвращает старый watermark).
                Attempt curAtt = Attempt.KIND_TEST.equals(a.kind)
                        ? cur.testAttempt : cur.attempt;
                return curAtt == null || curAtt.id <= a.id;
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.putLong(p + "id", a.id)
                        .putString(p + "kind", a.kind)
                        .putLong(p + "gen", a.targetGeneration)
                        .putString(p + "chat", a.chatName)
                        .putString(p + "channel", a.channelId)
                        .putInt(p + "notif_id", a.notificationId)
                        .putLong(p + "reading_ts", a.readingTs)
                        .putInt(p + "state", a.state);
            }

            @Override public Snapshot evolve(Snapshot c) {
                return new Snapshot(c.target, c.pending, c.blockedBy, c.blockedReason,
                        Attempt.KIND_TEST.equals(a.kind) ? c.attempt : a,
                        Attempt.KIND_TEST.equals(a.kind) ? a : c.testAttempt,
                        c.nextGen, c.lastHandoffMs, c.lastHandoffReadingTs,
                        c.lastTestHandoffMs);
            }
        };
    }

    /** Итог попытки без удаления pending (FAILED/UNKNOWN — повторное решение у протокола). */
    static boolean confirmAttemptState(Context app, Attempt updated) {
        return commitState(app, attemptWrite(updated));
    }

    /**
     * Успешная передача команды: одной записью отметить handoff и убрать
     * только СВОЁ ожидающее показание — более новое pending, записанное
     * позже, не удаляется (проверка по диску в момент записи).
     */
    static boolean confirmHanded(Context app, Attempt handed, Pending sentItem,
                                 boolean isReading, long nowMs) {
        final String p = attemptPrefix(handed);
        return commitState(app, new Mutation() {
            @Override public boolean appliesTo(Snapshot cur) {
                Attempt curAtt = isReading ? cur.attempt : cur.testAttempt;
                return curAtt == null || curAtt.id <= handed.id;
            }

            @Override public void apply(MaxStore store, MaxStore.Editor e, Snapshot cur) {
                e.putInt(p + "state", handed.state);
                if (isReading) {
                    e.putLong(KEY_HANDOFF_TS, nowMs)
                            .putLong(KEY_HANDOFF_READING_TS, sentItem.readingTs);
                    // Удаляем pending только если на диске всё ещё наше показание.
                    boolean stillOurs = sentItem.text.equals(
                            store.getString(KEY_PENDING_TEXT, null))
                            && store.getLong(KEY_PENDING_TS, -1) == sentItem.readingTs
                            && store.getLong(KEY_PENDING_GEN, -1) == sentItem.targetGeneration;
                    if (stillOurs) {
                        e.remove(KEY_PENDING_TEXT).remove(KEY_PENDING_TS).remove(KEY_PENDING_GEN);
                    }
                } else {
                    e.putLong(KEY_TEST_HANDOFF_TS, nowMs);
                }
            }

            @Override public Snapshot evolve(Snapshot c) {
                Pending pending = c.pending;
                if (isReading && pending != null
                        && pending.readingTs == sentItem.readingTs
                        && pending.targetGeneration == sentItem.targetGeneration) {
                    pending = null;
                }
                return new Snapshot(c.target, pending, c.blockedBy, c.blockedReason,
                        isReading ? handed : c.attempt,
                        isReading ? c.testAttempt : handed,
                        c.nextGen,
                        isReading ? nowMs : c.lastHandoffMs,
                        isReading ? sentItem.readingTs : c.lastHandoffReadingTs,
                        isReading ? c.lastTestHandoffMs : nowMs);
            }
        });
    }

    // --- Чтения (всегда из подтверждённого снимка) --------------------------------

    public static Target getTarget(Context context) {
        return state(context.getApplicationContext()).target;
    }

    public static Pending getPending(Context context) {
        return state(context.getApplicationContext()).pending;
    }

    public static Attempt getAttempt(Context context) {
        return state(context.getApplicationContext()).attempt;
    }

    public static Attempt getTestAttempt(Context context) {
        return state(context.getApplicationContext()).testAttempt;
    }

    public static String getBlockedBy(Context context) {
        return state(context.getApplicationContext()).blockedBy;
    }

    public static String getBlockedReason(Context context) {
        return state(context.getApplicationContext()).blockedReason;
    }

    public static long getLastHandoffMs(Context context) {
        return state(context.getApplicationContext()).lastHandoffMs;
    }

    static long getLastHandoffReadingTs(Context context) {
        return state(context.getApplicationContext()).lastHandoffReadingTs;
    }

    /** Следующее поколение выделяется внутри confirmTarget — снаружи не используется. */

    // --- Переключатели каналов -------------------------------------------------

    /** VK-канал включён по умолчанию — поведение существующих установок не меняется. */
    public static boolean isVkEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_VK_ENABLED, true);
    }

    /** MAX-канал выключен по умолчанию — на обновлённых установках не активируется сам. */
    public static boolean isMaxEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_MAX_ENABLED, false);
    }

    /** Установлено ли приложение MAX (сам мессенджер). */
    public static boolean maxAppInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MAX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // --- Идентичность чата --------------------------------------------------------

    /**
     * Тот ли это чат: имя, канал и id уведомления совпадают точно. Ослабления
     * нет ни для какого значения: имя и общий канал без подтверждённого id
     * не доказывают адресата — два одноимённых чата на одном канале разные.
     * Сохранённый id 0 — допустимый идентификатор и сравнивается как число;
     * отсутствие id (NOTIF_ID_UNKNOWN) ни с чем не совпадает — адресат требует
     * повторного выбора. Постоянство notificationId на всех версиях MAX на
     * устройстве не проверено; при его смене соответствие не подтверждается
     * — пользователь выбирает чат заново (безопасно).
     */
    static boolean matches(Target target, MaxReplyButton button) {
        if (target == null || target.notificationId == NOTIF_ID_UNKNOWN) return false;
        return button.chatName.equals(target.chatName)
                && button.channelId.equals(target.channelId)
                && button.notificationId == target.notificationId;
    }

    /**
     * Имя совпадает, но идентичность не подтверждена: возможно другой чат с
     * таким же названием. Такую кнопку нельзя принимать за выбранный чат.
     */
    static boolean isAmbiguous(Target target, MaxReplyButton button) {
        return target != null && button.chatName.equals(target.chatName)
                && !matches(target, button);
    }

    // --- Служебное -----------------------------------------------------------------

    /** Только тесты/«перезапуск процесса»: снять снимок и кеш handle — следующее чтение соберёт снимок с диска. */
    public static void resetForTest() {
        synchronized (MaxSender.class) {
            confirmed = null;
            cachedStore = null;
        }
    }
}
