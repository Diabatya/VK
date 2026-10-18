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
 * Единая точка доступа приложения к реквизитам (задание 2Г.4).
 * Один процессный экземпляр над CredentialStore: все Activity, receivers
 * и сервис читают один опубликованный снимок и ставят команды на общий
 * координатор storageId. Независимых кешей и собственных алгоритмов
 * выбора источника в вызывающем коде нет.
 *
 * Публикация состояния:
 * - published — последний ПОДТВЕРЖДЁННЫЙ снимок (результат завершённого
 *   resolve/команды), начальное значение LOADING;
 * - mutationPending — выставляется синхронно в момент запроса записи:
 *   до подтверждения операции snapshot() не выдаёт READY старой версии,
 *   новая отправка со старыми реквизитами не может начаться;
 * - generation — поколение запроса: поздний результат старого запроса
 *   не заменяет состояние более нового.
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
        /** Запрошенная операция ещё не подтверждена на диске. */
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
    private long generation;
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

    /**
     * Асинхронный resolve: состояние читается на worker координатора,
     * результат публикуется, если поколение запроса ещё актуально.
     */
    public Future<Resolution> resolveAsync() {
        final long gen = nextGeneration();
        Future<Resolution> f = store.resolveAsync();
        awaitPublish(f, gen, false);
        return f;
    }

    /**
     * Блокирующий resolve для фоновых потоков вызывающего (service worker,
     * switchExecutor). Запрещён на главном потоке и на worker
     * координатора: там ждать Future нельзя.
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
        try {
            Resolution r = resolveAsync().get(30, TimeUnit.SECONDS);
            return Snapshot.of(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Snapshot.incomplete();
        } catch (ExecutionException | TimeoutException e) {
            return Snapshot.incomplete();
        }
    }

    /** Resolve, результат-снимок доставляется на главный поток. */
    public void resolveOnMain(Consumer<Snapshot> callback) {
        deliverOnMain(resolveAsync(), callback);
    }

    /**
     * Resolve, результат-снимок доставляется на фоновый поток фасада —
     * для receivers и служебных путей, которым не нужен main.
     */
    public void resolveOnBackground(Consumer<Snapshot> callback) {
        final long gen = nextGeneration();
        Future<Resolution> f = store.resolveAsync();
        awaitPublish(f, gen, false);
        publishExec.execute(() -> {
            try {
                callback.accept(Snapshot.of(f.get()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                callback.accept(Snapshot.incomplete());
            }
        });
    }

    // --- команды изменения: немедленный запрет старой версии ---

    public Future<Resolution> setTokenAsync(String token) {
        return mutate(() -> store.setTokenAsync(token));
    }

    public Future<Resolution> deleteAsync() {
        return mutate(store::deleteAsync);
    }

    public Future<Resolution> setChatIdAsync(long chatId, String expectedTokenFingerprint) {
        return mutate(() -> store.setChatIdAsync(chatId, expectedTokenFingerprint));
    }

    public Future<Resolution> setAlertChatIdAsync(long alertChatId, String expectedTokenFingerprint) {
        return mutate(() -> store.setAlertChatIdAsync(alertChatId, expectedTokenFingerprint));
    }

    public Future<Resolution> clearChatIdsAsync(String expectedTokenFingerprint) {
        return mutate(() -> store.clearChatIdsAsync(expectedTokenFingerprint));
    }

    /** Команда записи; итоговый снимок доставляется на главный поток. */
    public void setTokenOnMain(String token, Consumer<Snapshot> callback) {
        deliverOnMain(setTokenAsync(token), callback);
    }

    public void setChatIdOnMain(long chatId, String expectedTokenFingerprint,
                              Consumer<Snapshot> callback) {
        deliverOnMain(setChatIdAsync(chatId, expectedTokenFingerprint), callback);
    }

    public void setAlertChatIdOnMain(long alertChatId, String expectedTokenFingerprint,
                                   Consumer<Snapshot> callback) {
        deliverOnMain(setAlertChatIdAsync(alertChatId, expectedTokenFingerprint), callback);
    }

    // --- внутреннее ---

    private long nextGeneration() {
        synchronized (lock) {
            return ++generation;
        }
    }

    /**
     * Мутация: поколение увеличивается и mutationPending ставится ДО
     * постановки команды — новый сетевой запуск со старым снимком
     * невозможен уже с момента запроса. Опубликованный ранее снимок
     * остаётся для начатой сетевой попытки (её протокол не прерывается).
     */
    private Future<Resolution> mutate(Supplier<Future<Resolution>> command) {
        final long gen;
        synchronized (lock) {
            gen = ++generation;
            mutationPending = true;
        }
        Future<Resolution> f = command.get();
        awaitPublish(f, gen, true);
        return f;
    }

    private void awaitPublish(Future<Resolution> f, long gen, boolean mutation) {
        publishExec.execute(() -> {
            try {
                Resolution r = f.get();
                synchronized (lock) {
                    if (gen == generation) {
                        if (mutation) mutationPending = false;
                        published = Snapshot.of(r);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                synchronized (lock) {
                    if (gen == generation) {
                        if (mutation) mutationPending = false;
                        published = Snapshot.incomplete();
                    }
                }
            }
        });
    }

    private void deliverOnMain(Future<Resolution> f, Consumer<Snapshot> callback) {
        publishExec.execute(() -> {
            Snapshot s;
            try {
                s = Snapshot.of(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                s = Snapshot.incomplete();
            }
            Snapshot result = s;
            mainHandler.post(() -> callback.accept(result));
        });
    }
}
