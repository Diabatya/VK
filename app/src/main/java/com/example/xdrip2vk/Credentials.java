package com.example.xdrip2vk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.store.AndroidCredentialBackend;
import com.example.xdrip2vk.store.CredentialBackend;
import com.example.xdrip2vk.store.CredentialStore;
import com.example.xdrip2vk.store.CredentialStore.Resolution;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Единая точка доступа приложения к реквизитам (задание 2Г.4, протокол
 * исправлен в 2Г.4А). Один процессный экземпляр над CredentialStore: все
 * Activity, receivers и сервис читают один опубликованный снимок и ставят
 * команды на общий координатор storageId. Независимых кешей и собственных
 * алгоритмов выбора источника в вызывающем коде нет.
 *
 * Протокол поколений:
 * - под {@link #lock} атомарно: присвоение номера запроса (generation),
 *   постановка операции в очередь координатора и постановка задачи
 *   публикации в publishExec. Порядок поколений == порядок операций ==
 *   порядок задач публикации — устаревший результат никогда не вытесняет
 *   более новый. Lock не удерживается во время ожидания Future или
 *   дисковых операций — только на постановку в очереди.
 * - mutationPending ставится синхронно при запросе записи: до
 *   подтверждения операции snapshot() не выдаёт READY старой версии.
 *   Снимается ТОЛЬКО завершением новейшей принятой записи
 *   (gen == latestMutationGen) — первая из двух записей не снимает запрет
 *   второй; промежуточные чтения запрет не отменяют и не отменяют учёт
 *   завершения записи.
 * - Результат, устаревший относительно более нового запроса, нигде не
 *   доставляется как актуальное состояние: resolveBlocking и все
 *   callback-доставки проходят {@link #effective} — проверку в момент
 *   ответа. Устаревший запрос получает текущее состояние фасада
 *   (с учётом запрета), а не сырой результат своей операции.
 *
 * Контракт callback'ов команд (setTokenOnMain и др.): доставленный
 * {@link CommandResult} — исход ЭТОЙ команды, никогда не заменяемый
 * состоянием других операций: отклонённая запись не становится успешной
 * от чужого READY. Флаг superseded отличает актуальный успех от
 * запоздавшего, замещённого более новым изменением. Результат команды
 * не является разрешением отправки — сервис читает актуальный снимок
 * по действующему протоколу.
 *
 * Потоки:
 * - snapshot() — только память, безопасен из любого потока, никогда не
 *   открывает Keystore и не инициирует миграцию;
 * - resolveAsync()/команды — работа на worker координатора (диск/Keystore
 *   вне главного потока), завершение публикуется с отдельного потока;
 * - resolveBlocking() — только для фоновых потоков вызывающего (не main,
 *   не worker координатора): сервисный worker ждёт Future, который
 *   выполняет чужой поток, — взаимной блокировки нет;
 * - *OnMain-варианты доставляют результат на главный поток.
 */
public final class Credentials {

    /** Состояние опубликованного снимка для UI/сервиса. */
    public enum State {
        /** Снимок ещё не загружен — фактические данные неизвестны. */
        LOADING,
        /** Подтверждённые реквизиты доступны. */
        READY,
        /** Реквизитов нет. */
        EMPTY,
        /** Пользователь подтверждённо удалил реквизиты. */
        DELETED,
        /** Конфликт/недоступность/повреждение — смотреть reason. */
        BLOCKED,
        /** Операция с реквизитами не подтверждена на диске. */
        INCOMPLETE
    }

    /** Неизменяемый снимок реквизитов из одной проверки. */
    public static final class Snapshot {
        public final State state;
        public final Reason reason;
        /** Токен — только при READY, иначе "". */
        public final String token;
        public final long chatId;
        public final long alertChatId;

        private Snapshot(State state, Reason reason,
                         String token, long chatId, long alertChatId) {
            this.state = state;
            this.reason = reason == null ? Reason.NONE : reason;
            this.token = token == null ? "" : token;
            this.chatId = chatId;
            this.alertChatId = alertChatId;
        }

        /** Отправка разрешена только подтверждённым READY-снимком. */
        public boolean sendPermitted() {
            return state == State.READY;
        }

        /** Токен и обычный адресат заданы — отправка имеет смысл. */
        public boolean configured() {
            return sendPermitted() && !token.isEmpty() && chatId > 0;
        }

        static Snapshot loading() {
            return new Snapshot(State.LOADING, Reason.NONE, "", 0, 0);
        }

        static Snapshot incomplete() {
            return new Snapshot(State.INCOMPLETE, Reason.NONE, "", 0, 0);
        }

        static Snapshot of(Resolution r) {
            if (r == null) return incomplete();
            switch (r.status) {
                case READY:
                    return new Snapshot(State.READY, r.reason,
                            r.token, r.chatId, r.alertChatId);
                case EMPTY:
                    return new Snapshot(State.EMPTY, r.reason, "", 0, 0);
                case DELETED:
                    return new Snapshot(State.DELETED, r.reason, "", 0, 0);
                case BLOCKED:
                    return new Snapshot(State.BLOCKED, r.reason, "", 0, 0);
                case INCOMPLETE:
                default:
                    return new Snapshot(State.INCOMPLETE, r.reason, "", 0, 0);
            }
        }
    }

    /**
     * Исход конкретной команды записи (2Г.4А.1).
     * outcome — результат ЭТОЙ команды: READY означает, что её commit
     * подтверждён; отказ сохраняет свою причину (включая STALE_TOKEN)
     * и никогда не подменяется чужим состоянием.
     * superseded — после этой команды принято более новое изменение:
     * запоздавший успех нельзя показывать как текущее состояние;
     * экран обновляется по {@link #snapshot()} фасада.
     */
    public static final class CommandResult {
        public final Snapshot outcome;
        public final boolean superseded;

        private CommandResult(Snapshot outcome, boolean superseded) {
            this.outcome = outcome;
            this.superseded = superseded;
        }
    }

    /**
     * Фиксированный текст состояния хранилища для UI — без значений
     * реквизитов и текста исключений. Ошибка хранилища не выглядит как
     * «токен не задан».
     */
    public static String describe(Snapshot s) {
        switch (s.state) {
            case BLOCKED:
                switch (s.reason) {
                    case TOKEN_MISMATCH:
                    case ADDRESS_MISMATCH:
                    case EMPTY_TOKEN_AMBIGUITY:
                    case SPLIT_CREDENTIALS:
                        return "Конфликт реквизитов в хранилищах";
                    case ENCRYPTED_UNAVAILABLE:
                    case SOURCE_UNAVAILABLE:
                        return "Защищённое хранилище недоступно";
                    case SOURCE_CORRUPT:
                    case SERVICE_STATE_INVALID:
                    case DESTINATION_MISMATCH:
                    case SERVICE_STATE_CHANGED:
                    case SERVICE_STATE_MISMATCH:
                        return "Хранилище реквизитов повреждено";
                    default:
                        return "Реквизиты заблокированы";
                }
            case INCOMPLETE:
                return "Операция с реквизитами не завершена";
            case LOADING:
                return "Проверка реквизитов…";
            case EMPTY:
                return "Токен не задан";
            case DELETED:
                return "Токен удалён";
            default:
                return "Готово";
        }
    }

    private static volatile Credentials instance;
    /** Тестовая подмена backend до первого get() — граница Keystore. */
    static volatile CredentialBackend backendForTest;
    /**
     * Robolectric исполняет runnables service worker на главном потоке
     * теста — там resolveBlocking ведёт себя как вызов с фонового worker.
     * Только для тестов; продакшн-защита от main-thread IO сохраняется.
     */
    static volatile boolean permitMainBlockingForTest;

    private final CredentialStore store;
    private final Handler mainHandler;
    /** Ожидание Future и публикация — не worker координатора, не main. */
    private final ExecutorService publishExec;
    private final Object lock = new Object();

    private Snapshot published = Snapshot.loading();
    /** Номер последнего выданного запроса (любого вида). */
    private long generation;
    /** Номер новейшей принятой записи; 0 — записей не было. */
    private long latestMutationGen;
    /** Есть запись, ещё не завершившаяся — снимок вне READY. */
    private boolean mutationPending;

    private Credentials(CredentialBackend backend) {
        this.store = new CredentialStore(backend);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.publishExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "credentials-publish");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized Credentials get(Context context) {
        if (instance == null) {
            CredentialBackend b = backendForTest != null
                    ? backendForTest
                    : new AndroidCredentialBackend(context.getApplicationContext());
            instance = new Credentials(b);
        }
        return instance;
    }

    static synchronized void resetForTest() {
        instance = null;
        backendForTest = null;
        permitMainBlockingForTest = false;
    }

    /** Для тестов: дождаться обработки очереди публикаций фасада. */
    void awaitPublishIdleForTest() {
        java.util.concurrent.CountDownLatch l =
                new java.util.concurrent.CountDownLatch(1);
        publishExec.execute(l::countDown);
        try {
            l.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Проверка: вызываем ли мы с рабочего потока координатора. */
    static boolean isOnCoordinatorThreadForTest() {
        Thread t = Thread.currentThread();
        return t.getName() != null && t.getName().startsWith("credential-store");
    }

    /**
     * Последний опубликованный снимок — только память, без IO.
     * Пока записанная команда не подтверждена, выдаёт INCOMPLETE вместо
     * устаревшего READY.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            return mutationPending ? Snapshot.incomplete() : published;
        }
    }

    /** Выданный запрос: номер поколения + Future результата. */
    private static final class Issued {
        final long gen;
        final Future<Resolution> future;
        Issued(long gen, Future<Resolution> future) {
            this.gen = gen;
            this.future = future;
        }
    }

    /**
     * Общая постановка операции. Под lock: номер запроса, enqueue в
     * координатор, enqueue публикации — порядок всех трёх одинаковый.
     * Ожидания Future и диска под lock нет.
     */
    private Issued issue(boolean mutation, Supplier<Future<Resolution>> command) {
        synchronized (lock) {
            long gen = ++generation;
            if (mutation) {
                latestMutationGen = gen;
                mutationPending = true;
            }
            Future<Resolution> f = command.get();
            enqueuePublish(f, gen, mutation);
            return new Issued(gen, f);
        }
    }

    /**
     * Публикация результата. Задачи publishExec идут строго в порядке
     * поколений, поэтому published — всегда новейший ЗАВЕРШИВШИЙСЯ
     * результат; stale-перезаписи невозможны.
     */
    private void enqueuePublish(Future<Resolution> f, long gen, boolean mutation) {
        publishExec.execute(() -> {
            try {
                Resolution r = f.get();
                synchronized (lock) {
                    if (mutation && gen == latestMutationGen) {
                        mutationPending = false;
                    }
                    published = Snapshot.of(r);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                synchronized (lock) {
                    if (mutation && gen == latestMutationGen) {
                        mutationPending = false;
                    }
                    published = Snapshot.incomplete();
                }
            }
        });
    }

    /**
     * Результат операции → снимок для вызывающего, с проверкой в момент
     * ответа: если с момента постановки принят более новый запрос или
     * запись ещё в полёте — возвращается текущее состояние фасада
     * (маскированное запретом), а не сырой устаревший результат.
     * Бесконечных повторных чтений нет: недостоверное — блокирующее.
     */
    private Snapshot effective(Resolution r, long gen) {
        synchronized (lock) {
            if (r != null && gen == generation && !mutationPending) {
                return Snapshot.of(r);
            }
            return mutationPending ? Snapshot.incomplete() : published;
        }
    }

    /**
     * Асинхронный resolve: состояние читается на worker координатора,
     * результат публикуется в порядке поколений.
     */
    public Future<Resolution> resolveAsync() {
        return issue(false, store::resolveAsync).future;
    }

    /**
     * Блокирующий resolve для фоновых потоков вызывающего (service worker,
     * switchExecutor). Запрещён на главном потоке и на worker
     * координатора: там ждать Future нельзя. Результат проходит проверку
     * актуальности: чтение, устаревшее относительно запроса записи,
     * отправку не разрешает; таймаут/ошибка — блокирующее состояние,
     * а не старый READY.
     */
    public Snapshot resolveBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper()
                && !permitMainBlockingForTest) {
            throw new IllegalStateException(
                    "resolveBlocking on main thread is forbidden");
        }
        if (isOnCoordinatorThreadForTest()) {
            throw new IllegalStateException(
                    "resolveBlocking on the credential worker would deadlock");
        }
        Issued op = issue(false, store::resolveAsync);
        try {
            return effective(op.future.get(30, TimeUnit.SECONDS), op.gen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Snapshot.incomplete();
        } catch (ExecutionException | TimeoutException e) {
            return Snapshot.incomplete();
        }
    }

    /** Resolve, актуальный на момент доставки снимок — на главный поток. */
    public void resolveOnMain(Consumer<Snapshot> callback) {
        deliverOnMain(issue(false, store::resolveAsync), callback);
    }

    /**
     * Resolve, актуальный на момент доставки снимок — на фоновый поток
     * фасада (для receivers и служебных путей).
     */
    public void resolveOnBackground(Consumer<Snapshot> callback) {
        final Issued op = issue(false, store::resolveAsync);
        publishExec.execute(() -> {
            Resolution r;
            try {
                r = op.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                r = null;
            }
            callback.accept(effective(r, op.gen));
        });
    }

    // --- команды изменения: немедленный запрет старой версии ---

    public Future<Resolution> setTokenAsync(String token) {
        return issue(true, () -> store.setTokenAsync(token)).future;
    }

    public Future<Resolution> deleteAsync() {
        return issue(true, store::deleteAsync).future;
    }

    public Future<Resolution> setChatIdAsync(long chatId, String expectedTokenFingerprint) {
        return issue(true, () -> store.setChatIdAsync(chatId, expectedTokenFingerprint)).future;
    }

    public Future<Resolution> setAlertChatIdAsync(long alertChatId, String expectedTokenFingerprint) {
        return issue(true, () -> store.setAlertChatIdAsync(alertChatId, expectedTokenFingerprint)).future;
    }

    public Future<Resolution> clearChatIdsAsync(String expectedTokenFingerprint) {
        return issue(true, () -> store.clearChatIdsAsync(expectedTokenFingerprint)).future;
    }

    /**
     * Команда записи; callback получает исход ИМЕННО этой команды
     * ({@link CommandResult}) на главном потоке.
     */
    public void setTokenOnMain(String token, Consumer<CommandResult> callback) {
        deliverCommandOnMain(issue(true, () -> store.setTokenAsync(token)), callback);
    }

    public void setChatIdOnMain(long chatId, String expectedTokenFingerprint,
                              Consumer<CommandResult> callback) {
        deliverCommandOnMain(issue(true, () -> store.setChatIdAsync(chatId, expectedTokenFingerprint)),
                callback);
    }

    public void setAlertChatIdOnMain(long alertChatId, String expectedTokenFingerprint,
                                     Consumer<CommandResult> callback) {
        deliverCommandOnMain(issue(true, () -> store.setAlertChatIdAsync(alertChatId, expectedTokenFingerprint)),
                callback);
    }

    // --- внутреннее ---

    private void deliverOnMain(Issued op, Consumer<Snapshot> callback) {
        publishExec.execute(() -> {
            Resolution r;
            try {
                r = op.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                r = null;
            }
            final Resolution res = r;
            // Проверка актуальности в момент фактической доставки: callback
            // мог ждать в очереди main уже после более нового запроса.
            mainHandler.post(() -> callback.accept(effective(res, op.gen)));
        });
    }

    /**
     * Доставка исхода команды: callback получает результат ЕЁ операции
     * (никогда не замещаемый чужим состоянием) + признак, что после неё
     * принято более новое изменение — проверка в момент фактической
     * доставки на main.
     */
    private void deliverCommandOnMain(Issued op, Consumer<CommandResult> callback) {
        publishExec.execute(() -> {
            Resolution r;
            try {
                r = op.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                r = null;
            }
            final Snapshot out = r == null
                    ? Snapshot.incomplete() : Snapshot.of(r);
            mainHandler.post(() -> {
                boolean superseded;
                synchronized (lock) {
                    superseded = latestMutationGen > op.gen;
                }
                callback.accept(new CommandResult(out, superseded));
            });
        });
    }
}
