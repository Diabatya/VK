package com.example.xdrip2vk.max;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.example.xdrip2vk.Logger;
import com.example.xdrip2vk.VkSenderService;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Отправка сформированного текста в выбранный чат MAX кнопкой «Ответить» —
 * так же, как если бы пользователь сам ответил на сообщение в шторке.
 * Сетью занимается сам MAX; успешный PendingIntent.send означает только
 * передачу команды приложению MAX, а не доставку.
 *
 * Единый владелец состояния (MAX-1Б): все изменения адресата, блокировки,
 * pending и результата отправки сериализуются одним рабочим исполнителем.
 * Слушатель уведомлений и экран настроек передают события на исполнитель,
 * не ожидая диска и PendingIntent.send на главном потоке; UI читает
 * опубликованный снимок MaxChannel.
 *
 * Протокол внешней команды: подтверждённая запись резерва попытки →
 * ревалидация → send → подтверждённая запись результата. Повторов после
 * успешной передачи нет; зарезервированная попытка без результата после
 * восстановления НЕ повторяется — процесс мог умереть после реальной
 * передачи (исход неизвестен, следующее НОВОЕ показание идёт обычным путём).
 */
public final class MaxSender {

    private static final String TAG = "MaxSender";

    /** Граница «команда в MAX» — в тестах подменяется, на устройстве зовёт PendingIntent.send. */
    public interface CommandSender {
        void send(Context context, PendingIntent pi, Intent fillIn) throws PendingIntent.CanceledException;
    }

    public static volatile CommandSender sendCommandForTest;
    public static volatile Function<PendingIntent, String> creatorPackageForTest;
    public static volatile LongSupplier clockForTest;
    /**
     * Тестовая точка «между проверкой и командой»: вызывается внутри общей
     * синхронизации непосредственно перед финальной ревалидацией и
     * PendingIntent.send.
     */
    public static volatile Runnable beforeCommandHookForTest;
    /**
     * Подмена рабочего исполнителя в тестах (прямое исполнение — синхронные
     * утверждения). null — реальный однопоточный исполнитель процесса.
     */
    public static volatile Executor workerForTest;

    private static volatile ScheduledExecutorService worker;

    private MaxSender() { }

    private static long now() {
        LongSupplier c = clockForTest;
        return c != null ? c.getAsLong() : System.currentTimeMillis();
    }

    /** Пакет-создатель PendingIntent: кнопка должна принадлежать MAX, а не нам и не чужому приложению. */
    private static String creatorPackage(PendingIntent pi) {
        Function<PendingIntent, String> f = creatorPackageForTest;
        if (f != null) return f.apply(pi);
        try {
            return pi.getCreatorPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Единый рабочий исполнитель ------------------------------------------------

    private static Executor worker() {
        Executor w = workerForTest;
        if (w != null) return w;
        ScheduledExecutorService s = worker;
        if (s == null) {
            synchronized (MaxSender.class) {
                s = worker;
                if (s == null) {
                    s = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "max-state-worker");
                        t.setDaemon(true);
                        return t;
                    });
                    worker = s;
                }
            }
        }
        return s;
    }

    /** Событие для владельца состояния — без ожидания на вызывающем потоке. */
    static void dispatch(Runnable task) {
        worker().execute(task);
    }

    /** Отложенная задача на рабочем исполнителе (повторы записей). */
    static void scheduleWorker(Runnable task, long delayMs) {
        Executor w = workerForTest;
        if (w != null) {
            task.run(); // в тестах — детерминированно, без реального ожидания
            return;
        }
        ((ScheduledExecutorService) worker()).schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    // --- Кандидаты для диалога выбора (неизменяемый снимок) ----------------------

    /**
     * Строка списка кандидатов: привязана к конкретной захваченной кнопке,
     * а не к позиции в живом списке — новые уведомления во время открытого
     * диалога не меняют то, что выберет пользователь.
     */
    public static final class Candidate {
        final MaxReplyButton button;

        Candidate(MaxReplyButton button) {
            this.button = button;
        }

        public String chatName() {
            return button.chatName;
        }

        public String kind() {
            return button.kind();
        }

        public long capturedAtMs() {
            return button.capturedAtMs;
        }

        /** Подпись строки: «имя — тип · время уведомления». */
        public String label() {
            String t = android.text.format.DateFormat.format("HH:mm", button.capturedAtMs).toString();
            return button.chatName + " — " + button.kind() + " · " + t;
        }
    }

    /** Снимок кандидатов: свежие сверху. Строки диалога строятся из него. */
    public static java.util.List<Candidate> candidates() {
        java.util.List<MaxReplyButton> list = MaxState.candidates();
        java.util.List<Candidate> out = new java.util.ArrayList<>(list.size());
        for (MaxReplyButton b : list) {
            out.add(new Candidate(b));
        }
        return out;
    }

    /**
     * Выбор чата с экрана настроек — событие уходит на рабочий исполнитель,
     * UI не ждёт диск. Результат возвращается на главный поток.
     */
    public static void selectCandidateAsync(Context context, Candidate candidate,
                                            Consumer<Boolean> resultOnMain) {
        Context app = context.getApplicationContext();
        dispatch(() -> {
            boolean ok = selectCandidateNow(app, candidate);
            new Handler(Looper.getMainLooper()).post(() -> resultOnMain.accept(ok));
        });
    }

    /**
     * Синхронный выбор кандидата — только с рабочего исполнителя или из
     * тестов. Экран настроек использует selectCandidateAsync.
     */
    public static boolean selectCandidate(Context context, Candidate candidate) {
        return selectCandidateNow(context.getApplicationContext(), candidate);
    }

    /**
     * Выбор чата пользователем: одна подтверждённая запись — адресат с новым
     * поколением, удаление старого pending и блокировки. Пока запись не
     * подтверждена, новая цель не используется.
     */
    static boolean selectCandidateNow(Context app, Candidate candidate) {
        if (candidate == null || candidate.button == null
                || candidate.button.pendingIntent == null) {
            return false;
        }
        synchronized (MaxSender.class) {
            MaxChannel.Target target = new MaxChannel.Target(
                    candidate.button.chatName, candidate.button.channelId,
                    candidate.button.notificationId, MaxChannel.nextGeneration(app));
            if (!MaxChannel.confirmTarget(app, target)) {
                Logger.w(TAG, "выбор чата не подтверждён записью — цель не меняется");
                return false;
            }
            MaxState.targetButton = candidate.button;
        }
        MaxReplyBackup.save(app, candidate.button);
        return true;
    }

    // --- События для владельца состояния (listener / сервис / UI) -----------------

    /**
     * Уведомление MAX с кнопкой «Ответить»: слушатель извлёк кнопку и передал
     * событие — дальше всё решает рабочий исполнитель.
     */
    static void onChatNotification(Context app, MaxReplyButton button, String chatName) {
        synchronized (MaxSender.class) {
            MaxChannel.Target wanted = MaxChannel.getTarget(app);
            if (wanted == null) return; // чат не выбран — только пополняем кандидатов

            if (MaxChannel.matches(wanted, button)) {
                // Подтверждённый чат: кнопка обновляется, блокировка снимается.
                MaxReplyButton saved = currentButton(app);
                boolean renewed = saved == null
                        || !Objects.equals(saved.pendingIntent, button.pendingIntent);
                MaxState.targetButton = button;
                if (MaxChannel.getBlockedBy(app) != null) {
                    MaxChannel.confirmClearBlocked(app);
                    Logger.i(TAG, "новое сообщение из выбранного чата — блокировка снята");
                }
                if (renewed) MaxReplyBackup.save(app, button);
                onButtonCapturedNow(app);
                return;
            }

            // Чужой чат с той же системной операцией ответа (Objects.equals
            // сравнивает операцию PendingIntent, а не Java-объект): ответ мог
            // бы уйти не туда — блокируем до нового уведомления выбранного чата.
            MaxReplyButton saved = currentButton(app);
            if (saved != null && Objects.equals(button.pendingIntent, saved.pendingIntent)) {
                MaxState.targetButton = null;
                MaxReplyBackup.clear(app);
                MaxChannel.confirmBlocked(app, MaxChannel.BLOCKED_CONFLICT, chatName);
                Logger.w(TAG, "у другого чата та же операция ответа — отправка заблокирована");
                return;
            }

            // Имя совпадает, но идентичность не подтверждена (другой
            // notificationId или идентификатор не сохранён): возможно
            // одноимённый чат — принимать его кнопку за выбранный чат нельзя.
            if (MaxChannel.isAmbiguous(wanted, button)) {
                MaxChannel.confirmBlocked(app, MaxChannel.BLOCKED_AMBIGUOUS, chatName);
                Logger.w(TAG, "одноимённый чат с другим идентификатором — нужен повторный выбор");
            }
        }
    }

    /** Событие «уведомление MAX с кнопкой» — слушатель не ждёт диск и send. */
    public static void dispatchNotification(Context app, MaxReplyButton button, String chatName) {
        dispatch(() -> onChatNotification(app, button, chatName));
    }

    /**
     * Свежепринятое показание из общего пайплайна (тот же текст, что уходит в VK).
     * Вызов — из рабочего потока VkSenderService после verdict ACCEPTED.
     */
    public static void onReading(Context context, String text, long readingTs) {
        Context app = context.getApplicationContext();
        dispatch(() -> onReadingNow(app, text, readingTs));
    }

    static void onReadingNow(Context app, String text, long readingTs) {
        synchronized (MaxSender.class) {
            MaxChannel.Snapshot snap = MaxChannel.state(app);
            if (!MaxChannel.isMaxEnabled(app)) return;
            if (snap.target == null) return;
            // Одно показание — одна команда: уже переданное не повторяем ни автоматом, ни вручную.
            if (readingTs == snap.lastHandoffReadingTs) return;
            // Хранится одно ожидающее: более новое заменяет предыдущее, старое новое не вытесняет.
            MaxChannel.Pending existing = snap.pending;
            if (existing == null || readingTs > existing.readingTs) {
                // pending пишется подтверждённой записью ДО попытки команды.
                if (!MaxChannel.confirmPending(app, text, readingTs,
                        snap.target.generation)) {
                    return; // запись не подтверждена — команду не начинаем
                }
            }
            MaxChannel.Pending current = MaxChannel.getPending(app);
            if (current != null) {
                trySend(app, current, true);
            }
        }
    }

    /**
     * Слушатель получил (новую или обновлённую) кнопку выбранного чата —
     * промываем ожидающее показание, если оно ещё актуально.
     */
    public static void onButtonCaptured(Context context) {
        Context app = context.getApplicationContext();
        dispatch(() -> onButtonCapturedNow(app));
    }

    static void onButtonCapturedNow(Context app) {
        synchronized (MaxSender.class) {
            if (!MaxChannel.isMaxEnabled(app)) return;
            MaxChannel.Snapshot snap = MaxChannel.state(app);
            MaxChannel.Pending pending = snap.pending;
            if (pending == null) return;
            // Pending живёт только в поколении своей цели и в сроке 15 минут.
            if (snap.target == null || pending.targetGeneration != snap.target.generation
                    || now() - pending.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
                MaxChannel.confirmClearPending(app);
                Logger.i(TAG, "отложенное показание неактуально — снято");
                return;
            }
            trySend(app, pending, true);
        }
    }

    /**
     * Тестовое сообщение: не откладывается и не считается показанием.
     * Ожидающий сахар не трогает: не очищает pending и не двигает watermark
     * последнего переданного показания — следующий flush отправит его один раз.
     * Вызывается с фонового потока настроек — ожидание результата допустимо,
     * главный поток не блокируется.
     */
    public static String sendTest(Context context) {
        Context app = context.getApplicationContext();
        FutureTask<String> task = new FutureTask<>(() -> sendTestNow(app));
        dispatch(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return statusText(app);
        } catch (java.util.concurrent.ExecutionException e) {
            return statusText(app);
        }
    }

    private static String sendTestNow(Context app) {
        if (!MaxChannel.isMaxEnabled(app)) return "Канал MAX выключен";
        if (MaxChannel.getTarget(app) == null) return "Чат MAX не выбран";
        String text = "Тест: связь xDrip2VK ↔ MAX работает.\nПоказания отправляются в выбранный чат.";
        MaxChannel.Pending item = new MaxChannel.Pending(text, now(),
                MaxChannel.getTarget(app).generation);
        boolean sent = trySend(app, item, false);
        return sent ? "Тестовое сообщение передано в MAX — доставка не подтверждена"
                : statusText(app);
    }

    // --- Протокол внешней команды ---------------------------------------------------

    /**
     * Передача команды. Тело — целиком под монитором MaxSender.class на общем
     * рабочем исполнителе: проверки, записи состояния и внешняя команда
     * атомарны относительно событий слушателя и выбора адресата.
     *
     * Порядок: гейты → подтверждённый резерв попытки → ревалидация →
     * PendingIntent.send → подтверждённый итог. Отказ записи запрещает
     * команду; отказ записи итога после send — не повод для повтора.
     */
    private static boolean trySend(Context app, MaxChannel.Pending item, boolean isReading) {
        MaxChannel.Snapshot snap = MaxChannel.state(app);
        MaxChannel.Target wanted = snap.target;
        if (wanted == null) {
            if (isReading) MaxChannel.confirmClearPending(app);
            return false;
        }
        if (!MaxChannel.isMaxEnabled(app)) return false;
        if (MaxState.storageError) {
            Logger.w(TAG, "не отправлено: хранилище состояния недоступно");
            return false;
        }
        // Общий Stop: ручная, авто и отложенная отправка запрещены; ожидающее сохраняем.
        if (!VkSenderService.isTransmissionAllowed(app)) {
            Logger.i(TAG, "не отправлено: передача остановлена");
            return false;
        }
        // Отзыв доступа слушателя запрещает отправку даже по сохранённой кнопке;
        // временное отключение (listenerConnected=false) запретом не является.
        if (!listenerAccessGranted(app)) {
            Logger.w(TAG, "не отправлено: доступ к уведомлениям отозван");
            return false;
        }
        if (snap.blockedBy != null) {
            Logger.w(TAG, "не отправлено: адресат не подтверждён — нужна новая кнопка или повторный выбор");
            return false;
        }
        // Pending чужого поколения новому адресату не достаётся.
        if (isReading && item.targetGeneration != wanted.generation) {
            MaxChannel.confirmClearPending(app);
            Logger.i(TAG, "ожидающее показание относится к прежнему адресату — снято");
            return false;
        }
        // Не повторять команду с неопределённым или подтверждённым исходом:
        // попытка покрывает это показание — повтор возможен только после FAILED.
        MaxChannel.Attempt covering = coveringAttempt(snap.attempt, item, isReading);
        if (covering != null) {
            Logger.w(TAG, "не отправлено: исход предыдущей передачи этого показания неизвестен");
            return false;
        }
        MaxReplyButton button = currentButton(app);
        if (button == null) {
            Logger.w(TAG, "не отправлено: нет кнопки ответа — нужно новое сообщение из выбранного чата");
            return false;
        }
        // Адресат и кнопка — один снимок: сохранённая/живая кнопка должна
        // принадлежать именно текущему выбранному чату.
        if (!MaxChannel.matches(wanted, button)) {
            Logger.w(TAG, "не отправлено: кнопка не от выбранного чата — нужно новое сообщение");
            if (MaxState.targetButton == button) MaxState.targetButton = null;
            return false;
        }
        if (!MaxChannel.MAX_PACKAGE.equals(creatorPackage(button.pendingIntent))) {
            Logger.w(TAG, "не отправлено: кнопка ответа не принадлежит MAX");
            return false;
        }
        if (isReading && now() - item.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
            MaxChannel.confirmClearPending(app);
            Logger.i(TAG, "показание устарело — не отправляю");
            return false;
        }

        // Подтверждённая запись резерва попытки — ДО внешней команды. Без неё
        // команду не вызываем: после восстановления RESERVED говорит «команда,
        // возможно, уже ушла» и запрещает автоматический повтор.
        MaxChannel.Attempt attempt = new MaxChannel.Attempt(now(),
                isReading ? MaxChannel.Attempt.KIND_READING : MaxChannel.Attempt.KIND_TEST,
                wanted.generation, wanted, item.readingTs,
                MaxChannel.Attempt.STATE_RESERVED);
        if (!MaxChannel.confirmReserveAttempt(app, attempt)) {
            Logger.w(TAG, "не отправлено: резерв попытки не подтверждён записью");
            return false;
        }

        // После записи резерва — полная ревалидация: за время commit могли
        // истечь срок, прийти Stop, отозвать доступ или возникнуть конфликт.
        if (!stillSendable(app, wanted, item, isReading)) {
            Logger.w(TAG, "не отправлено: запрет появился после резерва попытки");
            markAborted(app, attempt);
            return false;
        }

        Bundle results = new Bundle();
        for (RemoteInput ri : button.remoteInputs) {
            results.putCharSequence(ri.getResultKey(), item.text);
        }
        Intent fillIn = new Intent();
        RemoteInput.addResultsToIntent(button.remoteInputs, fillIn, results);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
        }

        // Разрыв «после проверки, до команды» — тестовая точка гонки. Затем
        // финальная ревалидация непосредственно перед командой.
        Runnable barrier = beforeCommandHookForTest;
        if (barrier != null) barrier.run();
        if (!stillSendable(app, wanted, item, isReading)) {
            Logger.w(TAG, "не отправлено: адресат/разрешения изменились перед командой");
            markAborted(app, attempt);
            return false;
        }

        try {
            CommandSender sender = sendCommandForTest;
            if (sender != null) {
                sender.send(app, button.pendingIntent, fillIn);
            } else {
                button.pendingIntent.send(app, 0, fillIn);
            }
            // Одна подтверждённая запись: итог HANDED + handoff + удаление
            // только СВОЕГО pending. Отказ записи здесь не повод для повтора
            // команды — RESERVED на диске запрещает повтор после восстановления.
            MaxChannel.confirmHanded(app,
                    attempt.withState(MaxChannel.Attempt.STATE_HANDED),
                    item, isReading, now());
            Logger.i(TAG, "передано в MAX — доставка не подтверждена");
            return true;
        } catch (PendingIntent.CanceledException e) {
            // MAX отменил кнопку — команда не ушла. Попытка FAILED: с новой
            // кнопкой выбранного чата ожидающее показание может отправиться.
            MaxReplyButton current = MaxState.targetButton;
            if (current == null || Objects.equals(current.pendingIntent, button.pendingIntent)) {
                MaxState.targetButton = null;
                MaxReplyBackup.clear(app);
            }
            MaxChannel.confirmAttemptState(app,
                    attempt.withState(MaxChannel.Attempt.STATE_FAILED));
            Logger.w(TAG, "не отправлено: MAX отменил кнопку ответа");
            return false;
        } catch (Throwable t) {
            // Любое другое исключение НЕ доказывает, что команда не была
            // передана — исход неизвестен: попытка не повторяется, показание
            // покрыто этой попыткой до нового чтения.
            MaxChannel.confirmAttemptState(app,
                    attempt.withState(MaxChannel.Attempt.STATE_UNKNOWN));
            MaxState.outcomeUnknown = true;
            Logger.w(TAG, "передача команды завершилась неопределённо — повтор запрещён");
            return false;
        }
    }

    /**
     * Попытка зарезервирована, но команда доказуемо не вызывалась (запрет на
     * ревалидации) — помечаем FAILED: повтор этого показания разрешён по
     * правилам обычного пути. Если и эта запись отказала — RESERVED остаётся
     * и консервативно подавляет повтор до нового чтения.
     */
    private static void markAborted(Context app, MaxChannel.Attempt attempt) {
        MaxChannel.confirmAttemptState(app,
                attempt.withState(MaxChannel.Attempt.STATE_FAILED));
    }

    /**
     * Попытка, покрывающая это показание: RESERVED (команда, возможно, ушла),
     * HANDED (команда точно передана) или UNKNOWN (исход неизвестен) — повтор
     * запрещён. FAILED (MAX отменил кнопку — не ушла) повтор разрешает.
     */
    private static MaxChannel.Attempt coveringAttempt(
            MaxChannel.Attempt attempt, MaxChannel.Pending item, boolean isReading) {
        if (attempt == null || attempt.state == MaxChannel.Attempt.STATE_FAILED) {
            return null;
        }
        String kind = isReading ? MaxChannel.Attempt.KIND_READING
                : MaxChannel.Attempt.KIND_TEST;
        return attempt.covers(item, kind) ? attempt : null;
    }

    /**
     * Ревалидация непосредственно перед внешней командой: всё, что могло
     * измениться с первой проверки — включённость канала, общий Stop, доступ
     * к уведомлениям, блокировка, выбранный адресат по сохранённой
     * идентичности и поколению, кнопка текущего адресата и срок показания.
     */
    private static boolean stillSendable(Context app, MaxChannel.Target wanted,
                                         MaxChannel.Pending item, boolean isReading) {
        if (!MaxChannel.isMaxEnabled(app)) return false;
        if (MaxState.storageError) return false;
        if (!VkSenderService.isTransmissionAllowed(app)) return false;
        if (!listenerAccessGranted(app)) return false;
        MaxChannel.Snapshot s = MaxChannel.state(app);
        if (s.blockedBy != null) return false;
        if (s.target == null || !s.target.sameIdentity(wanted)
                || s.target.generation != wanted.generation) {
            return false;
        }
        if (isReading && now() - item.readingTs > MaxChannel.PENDING_MAX_AGE_MS) {
            return false;
        }
        MaxReplyButton current = currentButton(app);
        return current != null && MaxChannel.matches(wanted, current);
    }

    /**
     * Кнопка ответа: из памяти, а после перезапуска процесса — из сохранённой
     * копии (MaxReplyBackup). null — кнопки нет, нужно новое сообщение из чата.
     */
    static MaxReplyButton currentButton(Context app) {
        MaxReplyButton b = MaxState.targetButton;
        if (b != null) return b;
        b = MaxReplyBackup.restore(app);
        if (b != null) {
            MaxState.targetButton = b;
            Logger.i(TAG, "кнопка ответа восстановлена после перезапуска");
        }
        return b;
    }

    /**
     * Подписи кандидатов для совместимости старого вызова — заменено candidates().
     */
    public static java.util.List<String> candidateLabels() {
        java.util.List<Candidate> list = candidates();
        java.util.List<String> labels = new java.util.ArrayList<>(list.size());
        for (Candidate c : list) {
            labels.add(c.label());
        }
        return labels;
    }

    /** Однострочное состояние канала для настроек/диагностики. */
    public static String statusText(Context context) {
        Context app = context.getApplicationContext();
        if (!MaxChannel.isMaxEnabled(app)) return "Канал MAX выключен";
        if (!MaxChannel.maxAppInstalled(app)) return "Приложение MAX не установлено";
        if (!listenerAccessGranted(app)) {
            return "Нет доступа к уведомлениям — откройте настройки и разрешите xDrip2VK";
        }
        if (MaxState.storageError) {
            // Фиксированный статус: без текста показания и данных уведомления.
            return "Ошибка записи состояния MAX — отправка приостановлена";
        }
        MaxChannel.Snapshot snap = MaxChannel.state(app);
        MaxChannel.Target wanted = snap.target;
        if (wanted == null) {
            return MaxState.candidates().isEmpty()
                    ? "Чат не выбран. Нужно сообщение из чата MAX — он появится в списке выбора"
                    : "Чат не выбран — откройте «Чат MAX» и выберите из списка";
        }
        if (!VkSenderService.isTransmissionAllowed(app)) return "Передача остановлена пользователем";
        if (snap.blockedBy != null) {
            return MaxChannel.BLOCKED_AMBIGUOUS.equals(snap.blockedReason)
                    ? "Адресат не подтверждён: есть другой чат с таким же именем — выберите чат заново"
                    : "Отправка заблокирована — нужно новое сообщение из выбранного чата MAX";
        }
        if (snap.attempt != null
                && (snap.attempt.state == MaxChannel.Attempt.STATE_RESERVED
                || snap.attempt.state == MaxChannel.Attempt.STATE_UNKNOWN)) {
            // Команда могла уйти, подтверждения нет — повтора не будет.
            return "Исход предыдущей передачи неизвестен";
        }
        if (currentButton(app) == null) {
            return "Нужно новое сообщение из выбранного чата MAX";
        }
        if (!MaxState.listenerConnected) {
            // Доступ есть, слушатель временно отключён системой — не запрет:
            // сохранённая кнопка пригодна, новые кнопки появятся после rebind.
            return "Слушатель уведомлений отключён — ожидание переподключения (кнопка сохранена)";
        }
        if (snap.lastHandoffMs > 0) {
            return "Передано в MAX — доставка не подтверждена ("
                    + android.text.format.DateFormat.format("HH:mm", snap.lastHandoffMs) + ")";
        }
        return "Готов к отправке в «" + wanted.chatName + "»";
    }

    /** Есть ли у приложения доступ слушателя уведомлений (без перечисления чужих пакетов). */
    public static boolean listenerAccessGranted(Context context) {
        android.content.ComponentName cn =
                new android.content.ComponentName(context, MaxNotificationListener.class);
        String enabled = android.provider.Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(cn.flattenToString());
    }

    /** Полный сброс состояния MAX в памяти и backup-копии — для тестов вне пакета max. */
    public static void resetRuntimeForTest(Context context) {
        MaxState.resetForTest();
        MaxChannel.resetForTest();
        MaxReplyBackup.clear(context);
    }
}
