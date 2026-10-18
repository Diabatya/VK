package com.example.xdrip2vk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import com.example.xdrip2vk.core.AlertThresholds;
import com.example.xdrip2vk.core.AuthContext;
import com.example.xdrip2vk.core.DeliveryCoordinator;
import com.example.xdrip2vk.core.Fingerprint;
import com.example.xdrip2vk.core.SgvReading;
import com.example.xdrip2vk.core.TreatmentEvent;
import com.example.xdrip2vk.core.Units;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VkSenderService extends Service {

    private static final String TAG = "VkSenderService";
    private static final String CHANNEL_ID = "VkSenderChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_UNITS = "units";
    private static final String PREF_DEVICE_NAME = "device_name";
    private static final String PREF_ALERT_ENABLED = "alert_enabled";
    private static final String PREF_ALERT_MIN_BG = "alert_min_bg";
    private static final String PREF_ALERT_MAX_BG = "alert_max_bg";
    private static final String PREF_SHOW_IOB = "show_iob";
    private static final String PREF_SHOW_COB = "show_cob";
    private static final String PREF_SHOW_TREATMENTS = "show_treatments";
    private static final String PREF_STATE = "delivery_state_v2";
    private static final String PREF_CONFIRMED_SENDER = "confirmed_sender";
    private static final String PREF_CONFIRMED_SENDER_FP = "confirmed_sender_fp";
    private static final String PREF_LAST_SENDER = "last_active_sender";
    /**
     * Сохранённое разрешение пользователя на передачу — источник выбора
     * пользователя, не состояние процесса. Отсутствие ключа — установка до
     * введения ключа: разрешено только если заданы токен и обычный чат
     * (прежнее поведение существующей установки).
     */
    static final String PREF_TRANSMISSION_ENABLED = "transmission_enabled";
    /** После явного «Запустить» первый снимок терапии — baseline без бэклога. */
    private static final String PREF_TREATMENT_BASELINE_PENDING = "treatment_baseline_pending";
    /**
     * Запрет уже запрошен, но commit ещё не завершён: новые отправки в этом
     * процессе блокируются сразу, не дожидаясь подтверждения записи.
     */
    private static volatile boolean userStopInFlight;

    /**
     * Неподтверждённый запрос разрешения: Start не смог записать
     * transmission_enabled=true на диск (отказ commit или запрос устарел во
     * время записи). Отказавший commit мог уже поменять значение в памяти —
     * поэтому разрешение считается НЕ подтверждённым до успешного актуального
     * Start. Снимается только в ветке успеха requestStartTransmission.
     */
    private static volatile boolean startWriteDenied;

    /**
     * На switchExecutor выполняется запись пользовательского выбора (Start/
     * Stop/миграция). Пока запись не завершена, кеш prefs мог быть уже
     * изменён ещё не подтверждённым commit — он не является источником
     * разрешения: действует последнее ПОДТВЕРЖДЁННОЕ состояние.
     */
    private static volatile boolean switchWriteInFlight;

    /** Выбор подтверждён на диске в этом процессе (или прочитан до записей). */
    private static volatile boolean choiceConfirmed;
    /** Значение последнего подтверждённого выбора. */
    private static volatile boolean confirmedEnabled;

    // ---- Передача владения состоянием доставки между экземплярами ----
    // Старый экземпляр может ещё дожимать сетевую попытку и писать checkpoint,
    // когда создан новый. Новый экземпляр НЕ загружает и не меняет очередь,
    // пока прежний владелец не сохранит результат и не передаст владение.
    private static final Object STATE_OWNER_LOCK = new Object();
    private static boolean stateOwned;
    /** Этот экземпляр держит владение состоянием доставки. */
    private volatile boolean ownsState;
    /** Активный (не завершающийся) экземпляр — владелец уведомления/будильника. */
    private static volatile VkSenderService activeInstance;

    // ---- Сериализация запросов Start/Stop ----
    // Запись разрешения — потенциально блокирующий commit, выполняется НЕ на
    // главном потоке. Все записи идут через один исполнитель; поколение
    // отсекает устаревший Start, завершившийся после более нового Stop.
    private static final Object SWITCH_LOCK = new Object();
    private static long switchGeneration;
    private static final java.util.concurrent.ExecutorService switchExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "VkSwitchThread"));
    /** Оба пути смены токена (ручной ввод и импорт файла) приходят сюда. */
    public static final String ACTION_TOKEN_CHANGED =
            "com.example.xdrip2vk.action.TOKEN_CHANGED";
    private static volatile boolean isRunning = false;
    private HandlerThread handlerThread;
    private Handler handler;
    private SharedPreferences prefs;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;

    private DeliveryCoordinator coordinator;
    private Runnable drainRunnable;
    private Runnable pollTask;
    private static final int ALARM_REQUEST_CODE = 1001;

    // Тестовая точка подмены отправителя для unit-тестов под Robolectric.
    static volatile DeliveryCoordinator.Sender senderOverride;

    // Тестовая точка подмены проверки владельца токена (без сети).
    static volatile java.util.function.Function<String, String> identityVerifierOverride;

    // Тестовые подмены ответов xDrip-вебсервера (без сети).
    static volatile java.util.function.Supplier<List<SgvReading>> bgDataOverride;
    static volatile java.util.function.Supplier<List<TreatmentEvent>> treatmentsOverride;

    /**
     * Хранилище состояния доставки с подтверждением записи: write() возвращает
     * false, если запись на диск не завершена (SharedPreferences.commit()).
     * Все обращения — только с рабочего потока сервиса.
     */
    interface StateStore {
        String read();

        boolean write(String json);
    }

    private final StateStore prefsStore = new StateStore() {
        @Override
        public String read() {
            return prefs.getString(PREF_STATE, "");
        }

        @Override
        public boolean write(String json) {
            // commit() на рабочем потоке — возврат подтверждает запись на диск.
            return prefs.edit().putString(PREF_STATE, json).commit();
        }
    };

    // Тестовая подмена хранилища: история записей и возможность отказа.
    static volatile StateStore stateStoreOverride;

    private StateStore store() {
        return stateStoreOverride != null ? stateStoreOverride : prefsStore;
    }

    /** Экземпляр завершается: новые poll/drain/send и перепланирование запрещены. */
    private volatile boolean shuttingDown;

    /** Worker умер по необработанному исключению — экземпляр неработоспособен. */
    volatile boolean workerFailed;

    /**
     * Аварийное состояние после ошибки worker-потока. Пересоздание пустого
     * потока создало бы видимость работы с потерянными задачами — вместо
     * этого экземпляр помечается неработоспособным и завершается:
     * isRunning=false, foreground-уведомление снимается в onDestroy, а
     * следующий запуск поднимает новый worker и очередь из подтверждённого
     * checkpoint. Два одновременно отправляющих worker не существует.
     * Вызывается из UncaughtExceptionHandler; прямой вызов — моделирование.
     */
    void onWorkerFailure(Thread thread, Throwable ex) {
        Logger.e(TAG, "Критическая ошибка в фоновом потоке: "
                + (ex == null ? "unknown" : ex.getClass().getSimpleName()));
        workerFailed = true;
        if (activeInstance == this) {
            isRunning = false; // сервис больше не считается работающим
        }
        // Маркер, адресованный этому экземпляру, он уже не обработает —
        // освобождаем; маркер более нового поколения (gen не совпадает) не
        // трогаем.
        releaseHintIfCurrent(instanceHintGen);
        handler = null; // поток мёртв — post'ы на него больше не принимаются
        releaseStateOwner(); // worker мёртв — записей состояния больше не будет
        stopSelf(); // упорядоченное завершение экземпляра на главном потоке
    }

    /**
     * Дождаться передачи владения состоянием доставки и занять его.
     * Вызывается с рабочего потока нового экземпляра: прежний владелец в это
     * время может дожимать сетевую попытку — параллельная загрузка и запись
     * очереди двумя экземплярами запрещена. Главный поток не блокируется.
     * @return false — экземпляр завершается/умер до получения владения.
     */
    private boolean acquireStateOwner() {
        synchronized (STATE_OWNER_LOCK) {
            while (stateOwned && !shuttingDown && !workerFailed) {
                try {
                    STATE_OWNER_LOCK.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (shuttingDown || workerFailed) {
                return false;
            }
            stateOwned = true;
            ownsState = true;
            return true;
        }
    }

    /** Передать владение: вызывается после последней записи состояния. */
    private void releaseStateOwner() {
        if (!ownsState) {
            return; // чужое владение не трогаем
        }
        synchronized (STATE_OWNER_LOCK) {
            ownsState = false;
            stateOwned = false;
            STATE_OWNER_LOCK.notifyAll();
        }
    }

    // Ограниченный повтор записи при отказе диска — без посекундного цикла.
    private static final long PERSIST_RETRY_MS = 30_000;
    private static final int PERSIST_RETRY_MAX = 5;
    private int persistRetries;

    /**
     * Разрешила ли пользовательская настройка передачу. Ключ — сохранённый
     * выбор пользователя; разрешение передачи и готовность конфигурации —
     * разные признаки. При отсутствии ключа выполняется ОДНОРАЗОВАЯ
     * инициализация: существующая настроенная установка (токен+чат) — true,
     * новая — false; дальнейшие настройки выбор не меняют. Запись
     * инициализации — асинхронно через switchExecutor; отказ не считается
     * завершённым — следующее чтение повторит вывод и попытку.
     */
    public static boolean isTransmissionAllowed(Context context) {
        if (userStopInFlight || startWriteDenied) {
            // запрет запрошен или разрешение не удалось подтвердить на диске —
            // изменённый в памяти prefs отказавшим commit — не доказательство
            return false;
        }
        if (switchWriteInFlight) {
            // Запись выбора выполняется прямо сейчас: кеш prefs уже может
            // содержать неподтверждённое значение — источником разрешения
            // служит последнее подтверждённое состояние (из выключенного/
            // неинициализированного состояния передача запрещена до успеха).
            return choiceConfirmed && confirmedEnabled;
        }
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.contains(PREF_TRANSMISSION_ENABLED) || migrationUnconfirmed) {
            boolean legacy = !TokenStorage.getToken(context).isEmpty()
                    && TokenStorage.getChatId(context) > 0;
            maybeInitTransmissionKey(context.getApplicationContext(), legacy);
            // Неподтверждённая инициализация не разрешает новые отправки:
            // ни в очереди, ни во время записи кеш prefs не является
            // источником — разрешение публикует только успешная запись.
            return false;
        }
        boolean v = p.getBoolean(PREF_TRANSMISSION_ENABLED, false);
        choiceConfirmed = true;
        confirmedEnabled = v; // чистое чтение — значение подтверждено диском
        return v;
    }

    /**
     * Инициализация/повтор выбора запланирован, но ещё не подтверждён —
     * вход в этот момент запрещён, но событие не должно теряться навсегда.
     * Процессный запрет пользователя (Stop) и неподтверждённый Start
     * цепочку проверок не поддерживают.
     */
    static boolean isConfirmationPending() {
        return migrationUnconfirmed && !userStopInFlight && !startWriteDenied;
    }

    private static final int RETRY_ALARM_REQUEST_CODE = 1002;
    private static final String EXTRA_CONFIRM_RETRY = "confirm_retry";
    /** Число уже назначенных автоматических проверок (переживает процесс). */
    private static final String PREF_CONFIRM_RETRY_COUNT = "confirm_retry_count";
    /** Бюджет автоматических повторов: интервалы 30/60/120/240/300 секунд. */
    private static final long[] CONFIRM_RETRY_DELAYS_MS =
            {30_000, 60_000, 120_000, 240_000, 300_000};
    /** Сигнал xDrip → опрос веб-сервиса. Broadcast extras не переносятся. */
    private static final String EXTRA_XDRIP_HINT = "xdrip_hint";
    /**
     * Внутреннее служебное поле — поколение сигнала в команде сервиса. Нужно,
     * чтобы запоздавшая отмена/завершение старого экземпляра не сняла маркер
     * более нового принятого сигнала.
     */
    static final String EXTRA_HINT_GEN = "xdrip_hint_gen";
    /** Не более одной обработки сигнала за интервал (объединение серии). */
    static final long HINT_MIN_INTERVAL_MS = 15_000;
    /**
     * Бэкстоп ожидающего маркера: сигнал принят системой, но команда так и не
     * дошла до обработки (потерян запуск). Бессрочного маркера быть не должно.
     */
    private static final long HINT_PENDING_TIMEOUT_MS = 30_000;
    private static final Object HINT_LOCK = new Object();
    private static long hintGenCounter;
    /** Поколение принятого, но ещё не обработанного сигнала; 0 — ожидающего нет. */
    private static long pendingHintGen;
    /** Монотонное время принятия ожидающего сигнала (для бэкстоп-таймаута). */
    private static long pendingHintAtMs;
    /** Монотонное время последнего принятого сигнала (receiver-лимит). */
    private static long lastHintAcceptedMs = -HINT_MIN_INTERVAL_MS;
    /** Монотонное время последнего реального опроса по сигналу (service-лимит). */
    private static long lastHintPollMs = -HINT_MIN_INTERVAL_MS;
    /** Поколение сигнала, адресованное этому экземпляру и им не обработанное. */
    private volatile long instanceHintGen;
    /** Подмена монотонных часов в тестах. */
    static volatile java.util.function.LongSupplier hintClockOverride;

    private static long hintElapsedMs() {
        java.util.function.LongSupplier c = hintClockOverride;
        return c != null ? c.getAsLong() : SystemClock.elapsedRealtime();
    }

    /**
     * Освободить ожидающий маркер, если он относится к указанному поколению.
     * Запоздавшая отмена старого сигнала маркер более нового не снимает.
     */
    private static void releaseHintIfCurrent(long gen) {
        if (gen == 0) {
            return;
        }
        synchronized (HINT_LOCK) {
            if (pendingHintGen == gen) {
                pendingHintGen = 0;
            }
        }
    }

    /**
     * Недоверенный сигнал xDrip → команда сервису. Ограничение частоты и
     * объединение выполняются ДО запуска сервиса: поток broadcast не создаёт
     * очередь запусков и задач. Повторная проверка — в handleXdripHint.
     */
    static void notifyXdripSignal(Context context) {
        long now = hintElapsedMs();
        long gen;
        synchronized (HINT_LOCK) {
            if (pendingHintGen != 0
                    && now - pendingHintAtMs > HINT_PENDING_TIMEOUT_MS) {
                pendingHintGen = 0; // принято, но до обработки не дошло — не вечно
            }
            if (pendingHintGen != 0
                    || now - lastHintAcceptedMs < HINT_MIN_INTERVAL_MS) {
                return; // серия сигналов объединяется в один опрос
            }
            lastHintAcceptedMs = now;
            pendingHintGen = ++hintGenCounter;
            pendingHintAtMs = now;
            gen = pendingHintGen;
        }
        try {
            Intent i = new Intent(context, VkSenderService.class);
            i.putExtra(EXTRA_XDRIP_HINT, true);
            i.putExtra(EXTRA_HINT_GEN, gen);
            context.startForegroundService(i);
        } catch (RuntimeException e) {
            releaseHintIfCurrent(gen);
            Logger.w(TAG, "Не удалось запустить сервис по сигналу xDrip");
        }
    }

    /** Тест: сервис взял сигнал в обработку — ожидающий маркер снят. */
    static void consumeHintForTest() {
        synchronized (HINT_LOCK) {
            pendingHintGen = 0;
        }
    }

    /** Момент срабатывания уже назначенной проверки; 0 — не назначена. */
    private static volatile long retryScheduledFor;

    /** Задача назначения проверки в очереди/в работе — входы её не накапливают. */
    private static volatile boolean retryScheduleQueued;
    private static final Object RETRY_SCHEDULE_LOCK = new Object();

    /** Тестовая подмена записи счётчика бюджета (отказ/удержание записи). */
    static volatile java.util.function.IntFunction<Boolean>
            retryCountWriteOverride;

    /** Требуется ли ещё проверка: нет запрета пользователя и подтверждённого запрета. */
    private static boolean retryCheckWorthwhile() {
        return !userStopInFlight && !startWriteDenied
                && !(choiceConfirmed && !confirmedEnabled);
    }

    /**
     * Одноразовый будильник повторной проверки: вход (boot/alarm/broadcast)
     * пришёл до подтверждения выбора. При срабатывании AlarmReceiver снова
     * выполняет gate — при подтверждённом разрешении запускает сервис, при
     * подтверждённом запрете (выбор пользователя) цепочка умирает.
     * Порядок на switchExecutor (диск вне главного потока, сериализация
     * резервирования): перепроверка → ПОДТВЕРЖДЁННАЯ запись расхода попытки
     * → повторная перепроверка Stop/актуальности → назначение. Отказ записи
     * счётчика — будильник не назначается, передача остаётся запрещённой.
     * Прерывание после записи счётчика считает попытку израсходованной —
     * бюджет не восстанавливается и не раздувается.
     */
    static void scheduleConfirmationRetry(Context context) {
        if (!isConfirmationPending()) {
            return;
        }
        if (retryScheduledFor > System.currentTimeMillis()) {
            // проверка уже назначена — повторные входы её не отодвигают
            return;
        }
        synchronized (RETRY_SCHEDULE_LOCK) {
            if (retryScheduleQueued) {
                return; // задача уже в очереди — входы её не накапливают
            }
            retryScheduleQueued = true;
        }
        final Context app = context.getApplicationContext();
        switchExecutor.execute(() -> {
            try {
                if (!retryCheckWorthwhile()
                        || retryScheduledFor > System.currentTimeMillis()) {
                    return; // пока ждали — Stop/запрет или чужое назначение
                }
                SharedPreferences p =
                        PreferenceManager.getDefaultSharedPreferences(app);
                int attempt = p.getInt(PREF_CONFIRM_RETRY_COUNT, 0);
                if (attempt >= CONFIRM_RETRY_DELAYS_MS.length) {
                    return; // бюджет исчерпан — передача остаётся запрещённой
                }
                // 1) расход попытки подтверждается на диске ДО назначения:
                // завершение процесса после записи не возвращает попытку.
                boolean budgetSaved = retryCountWriteOverride != null
                        ? retryCountWriteOverride.apply(attempt + 1)
                        : p.edit()
                                .putInt(PREF_CONFIRM_RETRY_COUNT, attempt + 1)
                                .commit();
                if (!budgetSaved) {
                    return; // отказ записи бюджета — будильник не назначается
                }
                // 2) за время записи мог прийти Stop/подтверждённый запрет —
                // назначение пропускаем; попытка считается израсходованной.
                if (!retryCheckWorthwhile()
                        || retryScheduledFor > System.currentTimeMillis()) {
                    return;
                }
                // 3) только теперь — назначение будильника.
                AlarmManager am =
                        (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (am == null) {
                    return;
                }
                Intent i = new Intent(app, AlarmReceiver.class);
                i.putExtra(EXTRA_CONFIRM_RETRY, true);
                PendingIntent pi = PendingIntent.getBroadcast(app,
                        RETRY_ALARM_REQUEST_CODE, i,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
                long t = System.currentTimeMillis()
                        + CONFIRM_RETRY_DELAYS_MS[attempt];
                try {
                    // не exact: SCHEDULE_EXACT_ALARM на API 31+ не требуется
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, t, pi);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, t, pi);
                    }
                } catch (SecurityException e) {
                    am.set(AlarmManager.RTC_WAKEUP, t, pi);
                }
                retryScheduledFor = t;
            } finally {
                retryScheduleQueued = false;
            }
        });
    }

    /** Сработал будильник повторной проверки — слот назначения свободен. */
    static void confirmationRetryFired() {
        retryScheduledFor = 0;
    }

    /**
     * Немедленная отмена цепочки повторных проверок (явный Stop): будильник
     * снимается независимо от результата записи запрета, запоздавшие
     * callbacks её не возобновляют.
     */
    static void cancelConfirmationRetry(Context context) {
        retryScheduledFor = 0;
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(context, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context,
                RETRY_ALARM_REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** Цепочка завершена подтверждённым выбором — бюджет обнуляется. */
    private static void resetConfirmRetryCount(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putInt(PREF_CONFIRM_RETRY_COUNT, 0).apply();
    }

    private static volatile boolean migrationInitQueued;
    /** Попытка инициализации ключа отказала — значение в памяти недоверенно. */
    private static volatile boolean migrationUnconfirmed;
    /** Значение последней попытки инициализации (0/1); -1 — не было. */
    private static volatile int migrationAttemptedValue = -1;

    private static void maybeInitTransmissionKey(final Context app, final boolean legacy) {
        if (migrationInitQueued) {
            return; // попытка уже в очереди — повторные чтения её не накапливают
        }
        // Неподтверждённое состояние ставится синхронно при постановке —
        // действует весь период «очередь → запись → обработка результата».
        migrationUnconfirmed = true;
        migrationInitQueued = true;
        switchExecutor.execute(() -> {
            try {
                SharedPreferences p =
                        PreferenceManager.getDefaultSharedPreferences(app);
                if (!p.contains(PREF_TRANSMISSION_ENABLED)
                        || migrationUnconfirmed) {
                    if (migrationAttemptedValue < 0) {
                        // Исходное решение миграции фиксируется при первой
                        // попытке — все повторы сохраняют тот же выбор
                        // независимо от дальнейших настроек токена/чатов.
                        migrationAttemptedValue = legacy ? 1 : 0;
                    }
                    // contains() после отказа не завершает инициализацию —
                    // значение в памяти могло остаться от отказавшего commit.
                    boolean target = migrationAttemptedValue != 0;
                    migrationUnconfirmed =
                            !writeTransmissionEnabled(app, target, false);
                    if (!migrationUnconfirmed) {
                        choiceConfirmed = true;
                        confirmedEnabled = target;
                        resetConfirmRetryCount(app); // цепочка завершена
                    }
                }
                // Иначе ключ подтверждён — либо ранее, либо подтверждённым
                // явным Start/Stop (он снимает migrationUnconfirmed при своей
                // успешной записи): устаревшая миграция его не перетирает.
            } finally {
                // отказ записи — инициализация не завершена, повтор при
                // следующем чтении (цикла по таймеру нет)
                migrationInitQueued = false;
            }
        });
    }

    /**
     * Тестовая подмена записи разрешения: (enabled, markBaselinePending) →
     * результат записи. Позволяет моделировать отказ commit с уже изменённым
     * кешем prefs.
     */
    static volatile java.util.function.BiFunction<Boolean, Boolean, Boolean>
            transmissionWriteOverride;

    /**
     * Подтверждённая запись пользовательского выбора; возобновление помечает
     * baseline терапии. На время записи выставляется switchWriteInFlight —
     * кеш prefs в этот момент не является источником разрешения.
     */
    private static boolean writeTransmissionEnabled(Context context, boolean enabled,
                                                    boolean markBaselinePending) {
        switchWriteInFlight = true;
        try {
            if (transmissionWriteOverride != null) {
                return transmissionWriteOverride.apply(enabled, markBaselinePending);
            }
            SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(PREF_TRANSMISSION_ENABLED, enabled);
            if (markBaselinePending) {
                e.putBoolean(PREF_TREATMENT_BASELINE_PENDING, true);
            }
            return e.commit();
        } finally {
            switchWriteInFlight = false;
        }
    }

    private static long nextSwitchGeneration() {
        synchronized (SWITCH_LOCK) {
            return ++switchGeneration;
        }
    }

    private static boolean isSwitchGenerationCurrent(long gen) {
        synchronized (SWITCH_LOCK) {
            return gen == switchGeneration;
        }
    }

    /**
     * «Остановить»: процессный запрет — немедленно на вызывающем потоке;
     * подтверждённая запись и завершение экземпляра — на switchExecutor.
     * @param done результат записи для UI (поток switchExecutor); null — без.
     */
    public static void requestStopTransmission(Context context,
                                               java.util.function.Consumer<Boolean> done) {
        userStopInFlight = true; // новые попытки блокируются до конца записи
        nextSwitchGeneration(); // устаревает любой ожидающий запрос Start
        final Context app = context.getApplicationContext();
        // Цепочка проверок неподтверждённой инициализации прекращается
        // немедленно — независимо от результата записи запрета.
        cancelConfirmationRetry(app);
        switchExecutor.execute(() -> {
            boolean saved = writeTransmissionEnabled(app, false, false);
            if (saved) {
                // Подтверждённый явный Stop — приоритет над миграцией.
                migrationUnconfirmed = false;
                choiceConfirmed = true;
                confirmedEnabled = false;
                resetConfirmRetryCount(app);
            }
            app.stopService(new Intent(app, VkSenderService.class));
            if (done != null) {
                done.accept(saved);
            }
        });
    }

    public static void requestStopTransmission(Context context) {
        requestStopTransmission(context, null);
    }

    /**
     * «Запустить»: только после ПОДТВЕРЖДЁННОЙ записи разрешения снимается
     * процессный запрет и стартует сервис. Устаревший запрос (пришёл более
     * новый Stop/Start) не снимает запрет и не запускает передачу.
     * Первый валидный снимок терапии после возобновления — baseline без бэклога.
     * @param done true — разрешение записано и сервис запрошен; false — отказ
     *             записи или запрос устарел (поток switchExecutor); null — без.
     */
    public static void requestStartTransmission(Context context,
                                                java.util.function.Consumer<Boolean> done) {
        final Context app = context.getApplicationContext();
        final long gen = nextSwitchGeneration();
        switchExecutor.execute(() -> {
            boolean ok = false;
            boolean writeOk = false;
            if (isSwitchGenerationCurrent(gen)) {
                writeOk = writeTransmissionEnabled(app, true, true);
                if (writeOk) {
                    // Подтверждённый явный выбор — приоритет над миграцией:
                    // повторная попытка миграции его не перезапишет.
                    migrationUnconfirmed = false;
                }
            }
            // Запись успела, но пришёл более новый запрос — эффектов старого
            // Start не применяем: запрет не снимаем, сервис не стартуем.
            if (writeOk && isSwitchGenerationCurrent(gen)) {
                userStopInFlight = false;
                startWriteDenied = false; // разрешение подтверждено на диске
                choiceConfirmed = true;
                confirmedEnabled = true;
                resetConfirmRetryCount(app); // новая цепочка от подтверждённого выбора
                cancelConfirmationRetry(app); // сервис запускает сам Start
                app.startForegroundService(new Intent(app, VkSenderService.class));
                ok = true;
            } else {
                // Запись не подтверждена: значение в памяти могло уже
                // поменяться отказавшим commit — до успешного Start любые
                // проверки разрешения возвращают запрет.
                startWriteDenied = true;
            }
            if (done != null) {
                done.accept(ok);
            }
        });
    }

    public static void requestStartTransmission(Context context) {
        requestStartTransmission(context, null);
    }

    /** Дождаться завершения всех запрошенных Start/Stop — только для тестов. */
    static void awaitSwitchIdleForTest() {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        switchExecutor.execute(latch::countDown);
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Поставить задачу в очередь переключателя — только для тестов. */
    static void enqueueSwitchTaskForTest(Runnable r) {
        switchExecutor.execute(r);
    }

    /** Сброс процессных флагов и владения состоянием — только для тестов. */
    static void resetTransmissionFlagsForTest() {
        awaitSwitchIdleForTest(); // сначала добить очередь — старые записи не протекают в следующий тест
        userStopInFlight = false;
        startWriteDenied = false;
        switchWriteInFlight = false;
        choiceConfirmed = false;
        confirmedEnabled = false;
        activeInstance = null;
        migrationInitQueued = false;
        migrationUnconfirmed = false;
        migrationAttemptedValue = -1;
        retryScheduledFor = 0;
        retryScheduleQueued = false;
        synchronized (HINT_LOCK) {
            pendingHintGen = 0;
            pendingHintAtMs = 0;
            hintGenCounter = 0;
            lastHintAcceptedMs = -HINT_MIN_INTERVAL_MS;
            lastHintPollMs = -HINT_MIN_INTERVAL_MS;
        }
        hintClockOverride = null;
        synchronized (STATE_OWNER_LOCK) {
            stateOwned = false;
            STATE_OWNER_LOCK.notifyAll();
        }
    }

    /**
     * Единая точка оповещения о смене токена — ручной ввод и импорт файла
     * вызывают одинаковую процедуру: инвалидация прежней проверки и
     * повторная верификация новой версии на рабочем потоке сервиса.
     * При запрещённой передаче настройка сохраняется, но сервис не
     * запускается — смена токена не включает передачу.
     */
    public static void notifyTokenChanged(Context context) {
        if (!isTransmissionAllowed(context)) {
            if (isConfirmationPending()) {
                scheduleConfirmationRetry(context);
            }
            return;
        }
        Intent i = new Intent(context, VkSenderService.class);
        i.setAction(ACTION_TOKEN_CHANGED);
        context.startService(i);
    }

    // Последняя реально назначенная задержка drainRunnable (для тестов планировщика).
    volatile long lastDrainDelayMs = -1;

    /** Looper фонового потока — для прокачки очереди в тестах. */
    android.os.Looper workerLooperForTest() {
        return handlerThread == null ? null : handlerThread.getLooper();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.d(TAG, "Сервис создан");
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xDrip2VK::ServiceWakeLock");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("xDrip → VK: Ожидание данных от сенсора"));

        if (!isTransmissionAllowed(this)) {
            // Запоздавший запуск при сохранённом запрете: foreground-вызов
            // уже выполнен (требование жизненного цикла), экземпляр сразу
            // завершается — ожидать данные при запрете нельзя.
            Logger.w(TAG, "Передача запрещена пользователем — экземпляр завершается");
            stopSelf();
            return;
        }
        isRunning = true;
        activeInstance = this;

        handlerThread = new HandlerThread("VkSenderThread");
        handlerThread.setUncaughtExceptionHandler(this::onWorkerFailure);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        drainRunnable = this::drainAndSchedule;
        pollTask = () -> pollXDrip(DeliveryCoordinator.Source.AUTO, true);

        // Все изменения состояния — только на фоновом потоке, строго по очереди.
        // Координатор загружается ПОСЛЕ передачи владения от прежнего
        // экземпляра — иначе его устаревший снимок перезапишет наши записи.
        handler.post(() -> {
            if (!acquireStateOwner()) {
                return; // экземпляр завершается — владение не получено
            }
            coordinator = DeliveryCoordinator.load(store().read());
            coordinator.setCheckpoint(this::persistState);
            if (prefs.getString(PREF_STATE, "").isEmpty()) {
                // Миграция со схемы 4.3: старый UI-кеш last_bg_* мог содержать
                // устаревшую глюкозу с ошибочно «текущим» временем. Он недоверен —
                // очищаем и не отправляем; валидные данные придут от источника.
                SharedPreferences.Editor editor = prefs.edit();
                editor.putFloat("last_bg_value", 0f);
                editor.putLong("last_bg_timestamp", 0);
                editor.putString("last_bg_direction", "");
                editor.apply();
                Logger.d(TAG, "Недоверенный кеш предыдущей версии очищен");
            }
            // Кандидат-кеш прежней схемы (candidate_bg_*) заполнялся из
            // недоверенного broadcast — путь восстановления удалён, ключи
            // инвалидируются, чтобы старый кандидат не создал сообщение.
            prefs.edit()
                    .remove("candidate_bg_value")
                    .remove("candidate_bg_timestamp")
                    .remove("candidate_bg_direction")
                    .apply();
            drainAndSchedule();
        });
        scheduleNextAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "Команда старта сервиса");
        // Поколение сигнала из команды — для освобождения маркера на всех
        // точках выхода (отклонение, отмена, гибель экземпляра до обработки).
        final long hintGen = intent != null
                ? intent.getLongExtra(EXTRA_HINT_GEN, 0) : 0;
        if (hintGen != 0) {
            instanceHintGen = hintGen;
        }
        if (workerFailed) {
            // Аварийный экземпляр: команду не «проглатываем» — START_STICKY
            // запрашивает у системы перезапуск рабочего экземпляра.
            releaseHintIfCurrent(hintGen);
            return START_STICKY;
        }
        if (!isTransmissionAllowed(this)) {
            // Запоздавшая команда (в т.ч. восстановление с null intent) при
            // сохранённом запрете: экземпляр завершается, перезапуск не нужен.
            releaseHintIfCurrent(hintGen);
            stopSelf();
            return START_NOT_STICKY;
        }
        isRunning = true;

        if (intent == null || handler == null) {
            releaseHintIfCurrent(hintGen);
            return START_STICKY;
        }
        final Intent cmd = intent;
        // Сериализуем: никаких изменений состояния на главном потоке.
        handler.post(() -> {
            if (shuttingDown || !isTransmissionAllowed(this)) {
                // Завершение/запрет — queued intent обхода не даёт; принятый,
                // но не обработанный сигнал не оставляет вечный маркер.
                releaseHintIfCurrent(hintGen);
                return;
            }
            boolean manualSend = cmd.getBooleanExtra("manual_send", false);
            boolean autoUpdate = cmd.getBooleanExtra("auto_update", false);
            boolean pollNow = cmd.getBooleanExtra("poll_now", false);
            boolean tokenChanged = ACTION_TOKEN_CHANGED.equals(cmd.getAction());

            if (tokenChanged) {
                // Не ждём следующего показания: проверка новой версии токена
                // и возобновление допустимой очереди — сразу на рабочем потоке.
                drainAndSchedule();
            } else if (manualSend) {
                // Ручная отправка берёт актуальное показание из веб-сервиса
                // xDrip — старый кеш неизвестного происхождения не источник.
                pollXDrip(DeliveryCoordinator.Source.MANUAL, true);
            } else if (cmd.getBooleanExtra(EXTRA_XDRIP_HINT, false)) {
                handleXdripHint(hintGen);
            } else if (autoUpdate) {
                SgvReading r = readingFromIntent(cmd);
                if (r == null) {
                    Logger.w(TAG, "Отклонено показание из intent: нет корректных данных");
                    return;
                }
                applyReading(r, DeliveryCoordinator.Source.AUTO);
            } else if (pollNow) {
                pollXDrip(DeliveryCoordinator.Source.AUTO, true);
            } else {
                drainAndSchedule();
            }
        });
        return START_STICKY;
    }

    /** Снимок показания из intent; bg_value принимается как любой Number (double/float). */
    private SgvReading readingFromIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        Object rawBg = extras.get("bg_value");
        if (!(rawBg instanceof Number)) {
            return null;
        }
        double bg = ((Number) rawBg).doubleValue();
        long ts = intent.getLongExtra("timestamp", 0);
        if (!(bg > 0) || !Double.isFinite(bg) || ts <= 0) {
            return null;
        }
        String direction = intent.getStringExtra("direction");
        return new SgvReading(bg, ts, direction == null ? "" : direction, "");
    }

    /**
     * Обработка сигнала xDrip на рабочем потоке. Повторно проверяются запрет
     * передачи (Stop во время ожидания) и частота: запоздавшие дубли команды
     * не создают лишних опросов.
     */
    private void handleXdripHint(long gen) {
        releaseHintIfCurrent(gen);
        synchronized (HINT_LOCK) {
            long now = hintElapsedMs();
            if (now - lastHintPollMs < HINT_MIN_INTERVAL_MS) {
                return;
            }
            lastHintPollMs = now;
        }
        if (!isTransmissionAllowed(this)) {
            return;
        }
        // Опрос по сигналу не отодвигает периодический будильник.
        pollXDrip(DeliveryCoordinator.Source.AUTO, false);
    }

    private void applyReading(SgvReading r, DeliveryCoordinator.Source src) {
        long now = System.currentTimeMillis();
        DeliveryCoordinator.Config cfg = loadConfig();
        DeliveryCoordinator.Messages msgs = buildMessages(XDripWebClient.fetchDeviceStatus());

        DeliveryCoordinator.ReadingOutcome outcome = coordinator.onReading(r, src, cfg, msgs, now);
        // Подтверждённая запись watermark/очереди/пропущенной тревоги — до drain.
        coordinator.persistNow();
        Logger.d(TAG, "Показание @ " + r.timestampMs
                + " (" + src + ") → " + outcome);

        if (outcome == DeliveryCoordinator.ReadingOutcome.ACCEPTED) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat("last_bg_value", (float) r.sgvMgdl);
            editor.putLong("last_bg_timestamp", r.timestampMs);
            editor.putString("last_bg_direction", r.direction);
            editor.apply();
        } else if (outcome == DeliveryCoordinator.ReadingOutcome.STALE
                || outcome == DeliveryCoordinator.ReadingOutcome.FUTURE) {
            updateNotification("Нет свежих данных от сенсора xDrip+: данные устарели или из будущего");
        }
        // Пропущенная из-за невыбранного чата тревога учтена в состоянии
        // coordinator и будет отражена в статусе доставки до истечения срока.
        drainAndSchedule();
    }

    private DeliveryCoordinator.Config loadConfig() {
        String token = TokenStorage.getToken(this);
        long chatId = TokenStorage.getChatId(this);
        long alertChatId = TokenStorage.getAlertChatId(this);
        boolean alertEnabled = prefs.getBoolean(PREF_ALERT_ENABLED, false);
        AlertThresholds thresholds = AlertThresholds.orDefault(
                prefs.getString(PREF_ALERT_MIN_BG, AlertThresholds.DEFAULT_MIN_STR),
                prefs.getString(PREF_ALERT_MAX_BG, AlertThresholds.DEFAULT_MAX_STR));
        boolean configured = !token.isEmpty() && chatId > 0;
        // Невыбранный чат — только id==0. Отрицательный peer_id — допустимый
        // адресат VK (сообщество по схеме messages.send), отбрасывать его по
        // знаку нельзя; пустая строка для id<=0 создавала бы ложный статус
        // «не выбран чат» и потерю тревоги.
        // Выбранные чаты принадлежат последнему подтверждённому аккаунту —
        // нельзя считать их адресатами нового токена до проверки владельца.
        return new DeliveryCoordinator.Config(
                chatId > 0 ? String.valueOf(chatId) : "",
                alertChatId != 0 ? String.valueOf(alertChatId) : "",
                alertEnabled, configured, thresholds,
                currentAuthContext(token),
                prefs.getString(PREF_LAST_SENDER, ""));
    }

    private DeliveryCoordinator.Messages buildMessages(XDripWebClient.DeviceStatusData status) {
        String units = prefs.getString(PREF_UNITS, "mmol");
        String deviceName = prefs.getString(PREF_DEVICE_NAME, "");
        boolean showIob = prefs.getBoolean(PREF_SHOW_IOB, false);
        boolean showCob = prefs.getBoolean(PREF_SHOW_COB, false);
        return new DeliveryCoordinator.Messages() {
            @Override
            public String regular(SgvReading r) {
                return buildBgMessage(r, status, null, units, deviceName, showIob, showCob);
            }

            @Override
            public String alert(SgvReading r, AlertThresholds.Level level) {
                String type = level == AlertThresholds.Level.LOW
                        ? "⚠️ ТРЕВОГА! Низкий сахар!"
                        : "⚠️ ТРЕВОГА! Высокий сахар!";
                return buildBgMessage(r, status, type, units, deviceName, showIob, showCob);
            }

            @Override
            public String treatment(TreatmentEvent e, boolean insulinPart) {
                String part = insulinPart
                        ? "инсулин: " + e.insulin + " ед"
                        : "углеводы: " + (int) e.carbs + "г";
                return deviceName.isEmpty()
                        ? "Отмечена терапия в xDrip — " + part
                        : deviceName + ": Отмечена терапия в xDrip — " + part;
            }
        };
    }

    private String buildBgMessage(SgvReading r, XDripWebClient.DeviceStatusData status,
                                  String alertType, String units, String deviceName,
                                  boolean showIob, boolean showCob) {
        String unitStr;
        if ("mmol".equals(units)) {
            unitStr = String.format(Locale.getDefault(), "%.1f ммоль/л", Units.mgdlToMmol(r.sgvMgdl));
        } else {
            unitStr = String.format(Locale.getDefault(), "%.0f мг/дл", r.sgvMgdl);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm dd.MM", Locale.getDefault());
        String timeStr = sdf.format(new Date(r.timestampMs));
        String arrow = getDirectionArrow(r.direction);
        String arrowStr = arrow.isEmpty() ? "" : " " + arrow;

        StringBuilder sb = new StringBuilder();
        if (!deviceName.isEmpty()) {
            sb.append(deviceName).append("\n");
        }
        if (alertType != null) {
            sb.append(alertType).append("\n");
        }
        sb.append("Глюкоза: ").append(unitStr).append(arrowStr).append(" (").append(timeStr).append(")");
        if (showIob && status.hasIob) {
            sb.append("\nИнсулин активный: ").append(String.format(Locale.getDefault(), "%.1f", status.iob)).append(" ед");
        }
        if (showCob && status.hasCob) {
            sb.append("\nУглеводы активные: ").append((int) status.cob).append("г");
        }
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true; // новые poll/drain/send и перепланирование запрещены
        // Если ожидающий маркер адресован этому экземпляру — его команда умрёт
        // вместе с worker, обработки не будет: освобождаем. Маркер более нового
        // поколения (адресованный новому экземпляру) не совпадает по gen и
        // сохраняется.
        releaseHintIfCurrent(instanceHintGen);
        // Передача владения новому экземпляру уже могла произойти: уведомление,
        // будильник и isRunning трогаем только если мы — активный экземпляр.
        final boolean wasActive = activeInstance == this;
        if (wasActive) {
            isRunning = false;
            activeInstance = null;
            cancelAlarms();
        }
        // Финальное сохранение, передача владения и остановка worker —
        // упорядоченно на том же потоке: задача встаёт в хвост ПОСЛЕ текущего
        // обхода, уже начатая сетевая попытка успевает завершиться и сохранить
        // результат (остаток обхода не отправляется — drain проверяет
        // shuttingDown). Только после последней записи владение отпускается —
        // новый экземпляр не загружает очередь параллельно с нами.
        // Главный поток сеть не ждёт.
        final Handler h = handler;
        final HandlerThread t = handlerThread;
        boolean shutdownPosted = false;
        if (h != null && t != null) {
            try {
                shutdownPosted = h.post(() -> {
                    // последний checkpoint: результат начатой попытки и всё «грязное»
                    if (coordinator != null) {
                        coordinator.persistNow();
                    }
                    releaseStateOwner();
                    t.quitSafely();
                });
            } catch (Exception ignored) {
                // looper уже мёртв — хвостовую задачу поставить нельзя
            }
        }
        if (!shutdownPosted) {
            // worker уже мёртв (аварийное состояние) или не создан: записей
            // от нас больше не будет — владение можно отпустить сразу.
            releaseStateOwner();
            if (t != null) {
                t.quitSafely();
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wasActive) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
        }
        super.onDestroy();
    }

    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Подтверждённая запись состояния на диск. Вызывается только с рабочего
     * потока (в т.ч. как checkpoint координатора между сетевыми попытками).
     * false — запись не подтверждена; состояние остаётся в памяти
     * (persistDirty) и будет записано повторно.
     */
    private boolean persistState() {
        if (coordinator == null) {
            return true;
        }
        return store().write(coordinator.save());
    }

    private String getDirectionArrow(String direction) {
        if (direction == null || direction.isEmpty()) return "";
        switch (direction) {
            case "DoubleUp": return "⇈";
            case "SingleUp": return "↑";
            case "FortyFiveUp": return "↗";
            case "Flat": return "→";
            case "FortyFiveDown": return "↘";
            case "SingleDown": return "↓";
            case "DoubleDown": return "⇊";
            default: return "";
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "xDrip to VK",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Уведомления о передаче данных глюкозы и терапии в ВКонтакте");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("xDrip → VK")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private volatile CharSequence lastNotificationText;

    /** Последний текст уведомления — для проверки статуса в тестах. */
    CharSequence notificationForTest() {
        return lastNotificationText;
    }

    /** Текущая очередь — для проверки восстановления в тестах. */
    java.util.List<com.example.xdrip2vk.core.PendingMessage> pendingForTest() {
        return coordinator == null
                ? java.util.Collections.emptyList()
                : coordinator.pendingMessages();
    }

    private void updateNotification(String text) {
        lastNotificationText = text;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }

    /**
     * Опрос фиксированного локального веб-сервиса xDrip. Единственный путь
     * показаний: недоверенный broadcast/кеши значений не используются.
     * {@code rescheduleAlarm} — сдвигать ли периодический будильник (сигнальные
     * опросы его не отодвигают).
     */
    private void pollXDrip(DeliveryCoordinator.Source src,
                           boolean rescheduleAlarm) {
        if (!isRunning || shuttingDown || !isTransmissionAllowed(this)) return;

        boolean acquired = false;
        try {
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(15 * 1000L);
                acquired = true;
            }

            String url = "http://127.0.0.1:17580/sgv.json";
            Logger.d(TAG, "Запрос данных от xDrip+ (веб-сервер): " + url);

            List<SgvReading> dataList = bgDataOverride != null
                    ? bgDataOverride.get() : XDripWebClient.fetchBgData(url);
            long now = System.currentTimeMillis();

            if (!dataList.isEmpty()) {
                SgvReading latest = dataList.get(0);
                Logger.d(TAG, "Получены данные от xDrip+: записей="
                        + dataList.size() + ", время=" + latest.timestampMs);
                applyReading(latest, src);
            } else {
                Logger.w(TAG, "Не получены данные от xDrip+ (веб-сервер не отвечает)");
                updateNotification("Нет связи с xDrip+: включите Web service в Inter-app settings xDrip (порт 17580)");
            }

            // Новые записи терапии (инсулин/углеводы) — все события в окне, не только одно.
            // Свежий снимок конфигурации: во время applyReading могла
            // подтвердиться смена владельца и сброс чатов — старый cfg устарел.
            DeliveryCoordinator.Config cfg = loadConfig();
            List<TreatmentEvent> treatments = treatmentsOverride != null
                    ? treatmentsOverride.get() : XDripWebClient.fetchTreatments();
            // Явное возобновление: первый ВАЛИДНЫЙ снимок — baseline без
            // бэклога за время остановки. null/ошибка baseline не завершают.
            // Признак снимается только ПОСЛЕ подтверждённой записи нового
            // baseline: прерывание до записи — baseline не сменился, флаг
            // жив; прерывание после записи, но до снятия флага — следующий
            // старт просто повторит baseline по новому снимку (события не
            // дублируются и не уходят бэклогом).
            boolean baselinePending =
                    prefs.getBoolean(PREF_TREATMENT_BASELINE_PENDING, false);
            if (baselinePending && treatments != null) {
                coordinator.resetTreatmentsBaseline();
            }
            boolean showTreatments = prefs.getBoolean(PREF_SHOW_TREATMENTS, false);
            coordinator.onTreatments(treatments, cfg,
                    buildMessages(new XDripWebClient.DeviceStatusData(0, 0)),
                    showTreatments, now);
            // Не только added>0: baseline терапии, просмотренные ID и watermark
            // — тоже изменения, их нужно подтвердить на диске.
            boolean persisted = coordinator.persistNow();
            if (baselinePending && treatments != null && persisted) {
                // baseline и его просмотренные события подтверждены на диске —
                // только теперь признак ожидания можно снять.
                prefs.edit().remove(PREF_TREATMENT_BASELINE_PENDING).commit();
            }

            if (isRunning && !shuttingDown && rescheduleAlarm) {
                scheduleNextAlarm();
            }
        } catch (Exception e) {
            // На пути данных исключение может нести фрагмент ответа —
            // в лог только класс исключения.
            Logger.e(TAG, "Ошибка при опросе xDrip+: "
                    + e.getClass().getSimpleName());
        } finally {
            if (acquired && wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception e) {
                    Logger.e(TAG, "Ошибка при освобождении WakeLock: " + e.getMessage());
                }
            }
        }
        drainAndSchedule();
    }

    /**
     * Auth-контекст представленного токена: fingerprint + подтверждённый
     * владелец. Подтверждённая привязка повторно используется для того же
     * fingerprint; для новой версии токена нужна новая проверка.
     */
    private AuthContext currentAuthContext(String token) {
        if (token.isEmpty()) {
            return AuthContext.NONE;
        }
        String fp = Fingerprint.of(token);
        if (fp.isEmpty()) {
            return AuthContext.NONE;
        }
        String confirmedFp = prefs.getString(PREF_CONFIRMED_SENDER_FP, "");
        if (fp.equals(confirmedFp)) {
            return AuthContext.verified(fp,
                    prefs.getString(PREF_CONFIRMED_SENDER, ""));
        }
        return AuthContext.unverified(fp);
    }

    /**
     * Проверка владельца токена запросом VK на рабочем потоке. Результат
     * применяется только если токен за время проверки не сменился: устаревший
     * ответ для прежней версии не активирует отправку. Ошибка сети или
     * неопределённый ответ — не подтверждение. null → токен сменился.
     */
    private AuthContext verifyTokenOwner(String token, String fp) {
        String owner = identityVerifierOverride != null
                ? identityVerifierOverride.apply(token)
                : VkApi.fetchTokenOwner(token);
        String tokenNow = TokenStorage.getToken(this);
        if (!Fingerprint.of(tokenNow).equals(fp)) {
            return null; // токен сменился во время проверки
        }
        if (owner == null || owner.isEmpty()) {
            return AuthContext.unverified(fp);
        }
        prefs.edit()
                .putString(PREF_CONFIRMED_SENDER, owner)
                .putString(PREF_CONFIRMED_SENDER_FP, fp)
                .apply();
        onConfirmedSender(owner);
        return AuthContext.verified(fp, owner);
    }

    /**
     * Подтверждённая смена аккаунта: чаты прежнего отправителя сбрасываются —
     * их нужно выбрать заново. Единицы, пороги и имя устройства сохраняются.
     * Старая очередь хранится в coordinator со своим владельцем до истечения.
     */
    private void onConfirmedSender(String owner) {
        String prev = prefs.getString(PREF_LAST_SENDER, "");
        if (!prev.isEmpty() && !prev.equals(owner)) {
            TokenStorage.setChatId(this, 0);
            TokenStorage.setAlertChatId(this, 0);
            Logger.w(TAG, "Подтверждён другой владелец токена — чаты нужно выбрать заново");
        }
        if (!owner.equals(prev)) {
            prefs.edit().putString(PREF_LAST_SENDER, owner).apply();
        }
    }

    private void drainAndSchedule() {
        if (coordinator == null || shuttingDown) {
            return; // завершение: уже поставленные callbacks не возобновляют работу
        }
        if (!isTransmissionAllowed(this)) {
            updateNotification("Передача остановлена пользователем");
            return; // запрет: ни отправок, ни перепланирования
        }
        String token = TokenStorage.getToken(this);
        if (token.isEmpty()) {
            if (coordinator.pendingCount() > 0) {
                updateNotification("Ошибка: не задан токен ВК или не выбран чат для отправки");
            }
            return;
        }
        String fp = Fingerprint.of(token);
        AuthContext auth = currentAuthContext(token);
        if (!auth.verified) {
            auth = verifyTokenOwner(token, fp);
            if (auth == null) {
                // Токен заменён во время проверки — цикл с новой версией.
                drainAndSchedule();
                return;
            }
        }
        final String tokenF = token;
        DeliveryCoordinator.Sender sender = senderOverride != null
                ? senderOverride
                : (peerId, text, randomId) -> VkApi.sendMessage(tokenF, peerId, text, randomId);

        // Непосредственно перед каждой сетевой попыткой (ПОСЛЕ последнего
        // блокирующего checkpoint) проверяется: представленный токен актуален,
        // завершение не началось, пользовательское разрешение не отозвано.
        DeliveryCoordinator.Summary s = coordinator.drain(sender, auth,
                () -> fp.equals(Fingerprint.of(TokenStorage.getToken(this)))
                        && !shuttingDown
                        && isTransmissionAllowed(this));
        // Финальный commit результатов обхода (внутри drain уже писались
        // checkpoint'и до/после каждой попытки).
        if (!coordinator.persistNow()) {
            s.persistFailed = true;
        }
        if (s.aborted) {
            if (shuttingDown) {
                return; // завершение: остаток обхода не отправляем
            }
            // Токен заменён в середине обхода: обрабатываем новую версию —
            // проверка владельца и допустимые отправки под ней.
            Logger.d(TAG, "Токен сменился во время обхода — повторная проверка");
            drainAndSchedule();
            return;
        }
        Logger.d(TAG, "Доставка: отправлено=" + s.sent + ", повторы=" + s.retried
                + ", auth=" + s.authFailures + ", permanent=" + s.permanent
                + ", expired=" + s.expired + ", persistFailed=" + s.persistFailed);

        if (s.persistFailed || coordinator.isPersistDirty()) {
            // Ограниченный повтор записи без посекундного цикла: после
            // PERSIST_RETRY_MAX ждём обычного пробуждения по очереди.
            persistRetries++;
            if (persistRetries <= PERSIST_RETRY_MAX) {
                lastDrainDelayMs = PERSIST_RETRY_MS;
                handler.removeCallbacks(drainRunnable);
                handler.postDelayed(drainRunnable, PERSIST_RETRY_MS);
            }
            updateDeliveryStatus(s, auth);
            return;
        }
        persistRetries = 0;

        updateDeliveryStatus(s, auth);

        long due = coordinator.nextDueAt(auth);
        if (due > 0 && !shuttingDown) {
            long delay = Math.max(due - System.currentTimeMillis(), 1000);
            lastDrainDelayMs = delay; // для тестов планировщика
            handler.removeCallbacks(drainRunnable);
            handler.postDelayed(drainRunnable, delay);
        } else if (!shuttingDown) {
            lastDrainDelayMs = -1;
        }
    }

    /**
     * Статус уведомления по фактическому состоянию очереди, а не только по
     * последнему Summary: успех одного сообщения не должен скрывать
     * сохраняющуюся ошибку другого, а для terminal/permanent нельзя
     * обещать повтор.
     */
    private void updateDeliveryStatus(DeliveryCoordinator.Summary s, AuthContext auth) {
        long now = System.currentTimeMillis();
        if (s.persistFailed || coordinator.isPersistDirty()) {
            // Запись на диск не подтверждена — безусловный успех не показываем;
            // состояние в памяти цело, запланирован ограниченный повтор записи.
            updateNotification("Ошибка сохранения очереди на диск — сообщения в памяти сохранены, повтор записи запланирован");
            return;
        }
        if (coordinator.isAuthBlocked(auth.tokenFp)) {
            updateNotification("Ошибка авторизации VK — проверьте токен в настройках");
            return;
        }
        if (!auth.verified) {
            updateNotification("Не удалось подтвердить владельца токена — отправка заблокирована");
            return;
        }
        int pending = coordinator.pendingCount();
        int terminal = coordinator.terminalCount();
        // Сообщения другого подтверждённого аккаунта или старой схемы без
        // владельца — заблокированы до истечения срока, а не «ждут повтора».
        int blocked = coordinator.blockedCount(auth);
        int eligible = pending - blocked;
        String blockedNote = blocked > 0
                ? "очередь прежнего аккаунта или версии заблокирована — выберите чаты заново"
                : null;
        // Пропущенная из-за невыбранного чата тревога — сохраняющееся состояние,
        // а не одноразовый текст: её не должен скрывать поздний успех других
        // сообщений. После истечения срока явно сообщаем, что она не отправлена.
        long missedUntil = coordinator.missedAlertUntil();
        String missed = missedUntil > 0
                ? (now < missedUntil
                        ? "Тревога не отправлена: для тревог не выбран чат — укажите его в настройках"
                        : "Срок тревоги истёк — она не была отправлена (чат тревог не был выбран)")
                : null;
        if (eligible > 0) {
            long due = coordinator.nextDueAt(auth);
            if (due > 0) {
                StringBuilder b = new StringBuilder(
                        "Часть сообщений не доставлена, повтор через ")
                        .append(Math.max(due - now, 0) / 1000).append(" сек");
                if (terminal > 0) {
                    b.append(" (есть окончательные ошибки)");
                }
                if (blockedNote != null) {
                    b.append(" · ").append(blockedNote);
                }
                if (missed != null) {
                    b.append(" · ").append(missed);
                }
                updateNotification(b.toString());
                return;
            }
        }
        if (terminal > 0) {
            updateNotification("Часть сообщений не доставлена — окончательная ошибка, повтор не планируется"
                    + (blockedNote != null ? " · " + blockedNote : "")
                    + (missed != null ? " · " + missed : ""));
            return;
        }
        if (blockedNote != null) {
            updateNotification("Часть сообщений не доставлена — " + blockedNote
                    + (missed != null ? " · " + missed : ""));
            return;
        }
        if (missed != null) {
            updateNotification(missed);
            return;
        }
        if (s.expired > 0) {
            updateNotification("Срок части сообщений истёк — данные устарели и не были отправлены");
            return;
        }
        if (s.sent > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            updateNotification("Успешно отправлено в ВК в " + sdf.format(new Date(now)));
        }
    }

    private void scheduleNextAlarm() {
        if (!isTransmissionAllowed(this)) {
            return; // запрет: будильники не восстанавливаются
        }
        long interval = 300000; // 5 минут по умолчанию
        try {
            interval = Long.parseLong(prefs.getString("poll_interval", "300000"));
        } catch (Exception e) {
            Logger.e(TAG, "Некорректный интервал опроса в настройках, используется 5 минут по умолчанию");
        }

        long triggerTime = System.currentTimeMillis() + interval;

        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (alarmManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
                Logger.d(TAG, "Следующий точный запрос запланирован через " + (interval/1000) + " сек");
            } catch (SecurityException e) {
                Logger.w(TAG, "Exact alarm запрещён системой, используем fallback (не exact alarm)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
                Logger.d(TAG, "Следующий запрос (fallback) запланирован через " + (interval/1000) + " сек");
            }
        }
    }

    private void cancelAlarms() {
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            Logger.d(TAG, "Запросы остановлены");
        }
    }
}
