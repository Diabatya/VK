package com.example.xdrip2vk.store;

import static org.junit.Assert.*;

import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Read;
import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.store.CredentialStore.Pending;
import com.example.xdrip2vk.store.CredentialStore.Resolution;
import com.example.xdrip2vk.store.CredentialStore.Status;

import org.junit.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Настоящий CredentialStore с backend, моделирующим отдельно диск и кеш:
 * commit=false меняет только кеш, исключения чтения/записи и прерывания
 * между шагами задаются флагами/счётчиками.
 */
public class CredentialStoreTest {

    private static final String T_A = "SYNTH_TKN_A_q1";
    private static final String T_B = "SYNTH_TKN_B_w2";

    /** Одно значение: строка, число или «неправильный тип». */
    private static final class V {
        String s;
        Long n;
        boolean wrongType;

        static V str(String v) {
            V x = new V();
            x.s = v;
            return x;
        }

        static V num(long v) {
            V x = new V();
            x.n = v;
            return x;
        }

        static V wrong() {
            V x = new V();
            x.wrongType = true;
            return x;
        }
    }

    /**
     * Модель SharedPreferences: snapshot читает КЕШ; операции меняют кеш и
     * при commit()==true переносят на диск; commit=false оставляет диск,
     * но грязный кеш виден последующим чтениям — как у настоящих prefs.
     */
    private static class FakeBackend implements CredentialBackend {
        final Map<Role, Map<String, V>> disk = new EnumMap<>(Role.class);
        final Map<Role, Map<String, V>> cache = new EnumMap<>(Role.class);
        final Map<Role, Read> readState = new EnumMap<>(Role.class);
        Object storageId = new Object(); // id процессного хранилища
        int failWrites;      // сколько putCredentials вернут false
        boolean throwOnWrite, throwOnClear, throwOnSnapshot;
        final java.util.Set<Integer> throwWriteOn = new java.util.HashSet<>();
        final java.util.Set<Integer> failClearOn = new java.util.HashSet<>();
        final java.util.Set<Integer> failReadOn = new java.util.HashSet<>();
        int writeCalls, clearCalls;
        final AtomicInteger snapshots = new AtomicInteger();
        Runnable onNthSnapshot;
        volatile String threadName;
        // Удержание записи для тестов передачи worker без sleep.
        volatile java.util.concurrent.CountDownLatch putStarted;
        volatile java.util.concurrent.CountDownLatch putGate;

        FakeBackend() {
            for (Role r : Role.values()) {
                disk.put(r, new HashMap<String, V>());
                cache.put(r, new HashMap<String, V>());
                readState.put(r, Read.READABLE);
            }
        }

        /** Второй backend НАД ТЕМ ЖЕ процессным хранилищем (не новый процесс). */
        FakeBackend sameProcessStorage() {
            return new FakeBackend(disk, cache, storageId);
        }

        private FakeBackend(Map<Role, Map<String, V>> sharedDisk,
                            Map<Role, Map<String, V>> sharedCache,
                            Object id) {
            disk.putAll(sharedDisk);
            cache.putAll(sharedCache);
            for (Role r : Role.values()) {
                readState.put(r, Read.READABLE);
            }
            this.storageId = id;
        }

        void seed(Role r, String token, Long chat, Long alert) {
            put(r, "token", token == null ? null : V.str(token));
            put(r, "chat", chat == null ? null : V.num(chat));
            put(r, "alert", alert == null ? null : V.num(alert));
        }

        void putRaw(Role r, String key, V v) {
            put(r, key, v);
        }

        private void put(Role r, String key, V v) {
            if (v == null) {
                return;
            }
            disk.get(r).put(key, v);
            cache.get(r).put(key, v);
        }

        /** Данные только на диске — состояние после нового процесса. */
        FakeBackend reloadFromDisk() {
            FakeBackend fresh = new FakeBackend();
            for (Role r : Role.values()) {
                fresh.disk.get(r).putAll(disk.get(r));
                fresh.cache.get(r).putAll(disk.get(r));
            }
            return fresh;
        }

        @Override
        public Object storageId() {
            return storageId;
        }

        private Entry entry(Map<String, V> m, String key, boolean numeric) {
            V v = m.get(key);
            if (v == null) {
                return Entry.absent();
            }
            if (v.wrongType) {
                return Entry.wrongType();
            }
            if (numeric) {
                return v.n == null ? Entry.wrongType() : Entry.number(v.n);
            }
            return v.s == null ? Entry.wrongType() : Entry.string(v.s);
        }

        @Override
        public Source snapshot(Role role) {
            if (throwOnSnapshot) {
                throw new RuntimeException("snapshot failed");
            }
            int n = snapshots.incrementAndGet();
            if (onNthSnapshot != null && n == 4) {
                onNthSnapshot.run(); // изменение до перепроверки очистки
            }
            if (readState.get(role) != Read.READABLE
                    || failReadOn.contains(n)) {
                return readState.get(role) == Read.CORRUPT
                        ? Source.corrupt(role) : Source.unavailable(role);
            }
            Map<String, V> m = cache.get(role);
            return Source.of(role, Read.READABLE, entry(m, "token", false),
                    entry(m, "chat", true), entry(m, "alert", true));
        }

        @Override
        public boolean putCredentials(String token, long chatId,
                                      long alertChatId) {
            writeCalls++;
            cache.get(Role.ENCRYPTED).put("token", V.str(token));
            cache.get(Role.ENCRYPTED).put("chat", V.num(chatId));
            cache.get(Role.ENCRYPTED).put("alert", V.num(alertChatId));
            if (putStarted != null) {
                putStarted.countDown();
            }
            if (putGate != null) {
                try {
                    putGate.await(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwOnWrite || throwWriteOn.contains(writeCalls)) {
                throw new RuntimeException("write failed after cache");
            }
            if (failWrites > 0) {
                failWrites--; // commit=false: диск не изменён, кеш грязный
                return false;
            }
            disk.get(Role.ENCRYPTED).putAll(cache.get(Role.ENCRYPTED));
            return true;
        }

        @Override
        public boolean removeCredentials(Role role) {
            clearCalls++;
            Map<String, V> c = cache.get(role);
            c.remove("token");
            c.remove("chat");
            c.remove("alert");
            if (throwOnClear) {
                throw new RuntimeException("clear failed after cache");
            }
            if (failClearOn.contains(clearCalls)) {
                return false; // commit=false: на диске ключи остались
            }
            Map<String, V> d = disk.get(role);
            d.remove("token");
            d.remove("chat");
            d.remove("alert");
            return true;
        }
    }

    private static Resolution await(CredentialStore s) throws Exception {
        return s.resolveAsync().get(10, TimeUnit.SECONDS);
    }

    // --- обязательные сценарии ---

    @Test
    public void plainOnlyMigratesThenClears() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, -3L);
        CredentialStore store = new CredentialStore(b);

        Resolution r = await(store);
        assertEquals(Status.READY, r.status);
        assertTrue(r.sendPermitted());
        assertEquals(T_A, r.token);
        assertEquals(5L, r.chatId);
        assertEquals(-3L, r.alertChatId);
        assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        assertEquals(1, b.writeCalls);
        assertEquals(1, b.clearCalls);
        store.shutdown();
    }

    @Test
    public void defaultOnlyMigratesThenClears() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.DEFAULT, T_A, 7L, null);
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.READY, r.status);
        assertEquals(7L, r.chatId);
        assertTrue(b.disk.get(Role.DEFAULT).isEmpty());
    }

    @Test
    public void identicalCopiesClearOnlyExtraKeys() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, 0L);
        b.seed(Role.PLAIN, T_A, 5L, 0L);
        b.putRaw(Role.PLAIN, "other_pref", V.str("keep"));
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.READY, r.status);
        assertEquals(0, b.writeCalls); // уже в encrypted — не перезаписывать
        assertEquals(1, b.clearCalls);
        assertEquals("keep",
                b.disk.get(Role.PLAIN).get("other_pref").s);
        assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
    }

    @Test
    public void conflictWritesNothing() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_B, 5L, null);
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.BLOCKED, r.status);
        assertEquals(Reason.TOKEN_MISMATCH, r.reason);
        assertFalse(r.sendPermitted());
        assertEquals(0, b.writeCalls);
        assertEquals(0, b.clearCalls);
        assertEquals(T_B, b.disk.get(Role.PLAIN).get("token").s);
    }

    @Test
    public void encryptedUnavailableNeverWritesPlaintext() throws Exception {
        FakeBackend b = new FakeBackend();
        b.readState.put(Role.ENCRYPTED, Read.UNAVAILABLE);
        b.seed(Role.PLAIN, T_A, 5L, null);
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.BLOCKED, r.status);
        assertEquals(Reason.ENCRYPTED_UNAVAILABLE, r.reason);
        assertEquals(0, b.writeCalls);
        assertEquals(0, b.clearCalls);
    }

    @Test
    public void failedDestinationWriteKeepsSource() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore store = new CredentialStore(b);

        Resolution r = await(store);
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Pending.DESTINATION_WRITE, r.pending);
        assertFalse(r.sendPermitted());
        assertNull(b.disk.get(Role.ENCRYPTED).get("token")); // не записано
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s); // цел
        store.shutdown();
    }

    @Test
    public void retryAfterFailedWriteReallyRewrites() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore store = new CredentialStore(b);

        await(store); // провал: кеш encrypted грязный, диск пуст
        Resolution r2 = await(store); // повтор: не пропустить из-за кеша
        assertEquals(Status.READY, r2.status);
        assertEquals(2, b.writeCalls); // запись действительно повторена
        assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        store.shutdown();
    }

    @Test
    public void failedClearIsNotDeclaredComplete() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);

        Resolution r = await(store);
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Pending.SOURCE_CLEAR, r.pending);
        // на диске plain остался — в новом процессе он снова виден
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        store.shutdown();
    }

    @Test
    public void recoversAfterConfirmedWriteBeforeClear() throws Exception {
        // Прерывание после подтверждённой записи назначения до очистки:
        // новый процесс читает диск → копии → дедупликация без записи.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        CredentialStore first = new CredentialStore(b);
        b.failClearOn.add(1);
        await(first); // write ok, clear failed
        first.shutdown();

        FakeBackend b2 = b.reloadFromDisk();
        CredentialStore second = new CredentialStore(b2);
        Resolution r = await(second);
        assertEquals(Status.READY, r.status);
        assertEquals(0, b2.writeCalls); // назначение уже подтверждено
        assertTrue(b2.disk.get(Role.PLAIN).isEmpty());
        second.shutdown();
    }

    @Test
    public void recoversBetweenPlainAndDefaultClear() throws Exception {
        // Копии в plain+default: запись в enc, очистка plain ок,
        // прерывание перед очисткой default.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.seed(Role.DEFAULT, T_A, 5L, null);
        b.failClearOn.add(2); // второй clear (default) провалится
        CredentialStore first = new CredentialStore(b);
        Resolution r1 = await(first);
        assertEquals(Status.INCOMPLETE, r1.status);
        first.shutdown();

        FakeBackend b2 = b.reloadFromDisk();
        assertTrue(b2.disk.get(Role.PLAIN).isEmpty());   // подтверждено
        assertEquals(T_A, b2.disk.get(Role.DEFAULT).get("token").s);
        Resolution r2 = await(new CredentialStore(b2));
        assertEquals(Status.READY, r2.status);
        assertTrue(b2.disk.get(Role.DEFAULT).isEmpty());
    }

    @Test
    public void changedSourceIsNotCleared() throws Exception {
        // Источник изменился после снимка: новое значение не удаляется,
        // операция блокируется до новой проверки.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.onNthSnapshot = () -> {
            b.cache.get(Role.PLAIN).put("token", V.str(T_B));
            b.disk.get(Role.PLAIN).put("token", V.str(T_B));
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Pending.SOURCE_CHANGED, r.pending);
        assertEquals(T_B, b.disk.get(Role.PLAIN).get("token").s); // сохранён
        assertEquals(0, b.clearCalls);
    }

    @Test
    public void foreignSettingsQueueAndKeysetSurvive() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.DEFAULT, T_A, 5L, null);
        b.putRaw(Role.DEFAULT, "delivery_state_v2", V.str("QUEUE"));
        b.putRaw(Role.DEFAULT, "ui_pref", V.num(42));
        b.putRaw(Role.ENCRYPTED,
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
                V.str("KEYSET"));
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.READY, r.status);
        assertEquals("QUEUE",
                b.disk.get(Role.DEFAULT).get("delivery_state_v2").s);
        assertEquals(42L,
                (long) b.disk.get(Role.DEFAULT).get("ui_pref").n);
        assertEquals("KEYSET", b.disk.get(Role.ENCRYPTED).get(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__").s);
        // keyset не попал в реквизиты: encrypted пуст → токен из default
        assertEquals(T_A, r.token);
    }

    @Test
    public void largeLongAndNegativeAlertPreserved() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, Long.MAX_VALUE, -2000000001L);
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.READY, r.status);
        assertEquals(Long.MAX_VALUE, r.chatId);
        assertEquals(-2000000001L, r.alertChatId);
    }

    @Test
    public void wrongTypeBlocksNotMerges() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.putRaw(Role.PLAIN, "token", V.wrong());
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.BLOCKED, r.status);
        assertEquals(Reason.SOURCE_CORRUPT, r.reason);
        assertEquals(0, b.writeCalls);
    }

    @Test
    public void orphanCleanupWritesNoToken() throws Exception {
        FakeBackend b = new FakeBackend();
        b.putRaw(Role.DEFAULT, "chat", V.num(5L));
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.EMPTY, r.status);
        assertEquals(0, b.writeCalls); // пустой план — назначение не пишем
        assertTrue(b.disk.get(Role.DEFAULT).get("chat") == null);
    }

    @Test
    public void runsOffMainThread() throws Exception {
        FakeBackend b = new FakeBackend() {
            @Override
            public Source snapshot(Role role) {
                threadName = Thread.currentThread().getName();
                return super.snapshot(role);
            }
        };
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        CredentialStore store = new CredentialStore(b);
        await(store);
        assertEquals("credential-store", b.threadName);
        assertNotEquals(Thread.currentThread().getName(), b.threadName);
        store.shutdown();
    }

    // --- регрессии 2Г.3Б.1 ---

    @Test
    public void failedClearRetriedBySameStore() throws Exception {
        // Воспроизведение №1: чистый кеш давал ложный READY без повтора
        // очистки. Теперь долг очистки — конкретная операция координатора.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);
        try {
            Resolution r1 = await(store);
            assertEquals(Status.INCOMPLETE, r1.status);

            Resolution r2 = await(store);
            assertEquals(2, b.clearCalls); // реальный второй remove
            assertEquals(Status.READY, r2.status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    public void repeatedClearFailureStaysIncomplete() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        b.failClearOn.add(2);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Pending.SOURCE_CLEAR, r.pending);
            assertFalse(r.sendPermitted());
        } finally {
            store.close();
        }
    }

    @Test
    public void orphanClearFailureNotFalseEmpty() throws Exception {
        FakeBackend b = new FakeBackend();
        b.putRaw(Role.DEFAULT, "chat", V.num(5L));
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            // Повтор реально очистит, только затем EMPTY.
            assertEquals(Status.EMPTY, await(store).status);
            assertNull(b.disk.get(Role.DEFAULT).get("chat"));
        } finally {
            store.close();
        }
    }

    @Test
    public void newStoreDoesNotTrustDirtyEncryptedCache() throws Exception {
        // Воспроизведение №2: второй Store удалял plain по неподтверждённому
        // encrypted-кешу. Координатор общий — запись сначала повторяется.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore first = new CredentialStore(b);
        assertEquals(Status.INCOMPLETE, await(first).status);
        first.close(); // кеш backend НЕ сброшен — тот же процесс

        CredentialStore second = new CredentialStore(b);
        try {
            Resolution r = await(second);
            assertEquals(Status.READY, r.status);
            assertEquals(2, b.writeCalls); // повтор commit назначения
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        } finally {
            second.close();
        }
    }

    @Test
    public void newBackendSameStorageDoesNotBypass() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore first = new CredentialStore(b);
        assertEquals(Status.INCOMPLETE, await(first).status);
        first.close();

        FakeBackend b2 = b.sameProcessStorage(); // тот же диск/кеш/id
        CredentialStore second = new CredentialStore(b2);
        try {
            Resolution r = await(second);
            assertEquals(Status.READY, r.status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        } finally {
            second.close();
        }
    }

    @Test
    public void twoHandlesAreSerialized() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        CredentialStore s1 = new CredentialStore(b);
        CredentialStore s2 = new CredentialStore(b);
        try {
            Resolution r1 = s1.resolveAsync().get(10, TimeUnit.SECONDS);
            Resolution r2 = s2.resolveAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r1.status);
            assertEquals(Status.READY, r2.status);
            assertEquals(1, b.writeCalls);   // операция одна — не дубль
            assertEquals(1, b.clearCalls);
        } finally {
            s1.close();
            s2.close();
        }
    }

    @Test
    public void newCredsAfterFailedClearAreNotDeleted() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            // Между отказом и повтором источник получил новые реквизиты.
            b.cache.get(Role.PLAIN).put("token", V.str(T_B));
            b.disk.get(Role.PLAIN).put("token", V.str(T_B));
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Pending.SOURCE_CHANGED, r.pending);
            assertEquals(T_B, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            store.close();
        }
    }

    @Test
    public void newCredsAfterFailedWriteNotOverwrittenByOldPlan() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            // В encrypted попали новые реквизиты — старый план их не трогает.
            b.cache.get(Role.ENCRYPTED).put("token", V.str(T_B));
            b.disk.get(Role.ENCRYPTED).put("token", V.str(T_B));
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Pending.SOURCE_CHANGED, r.pending);
            assertEquals(T_B, b.disk.get(Role.ENCRYPTED).get("token").s);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            store.close();
        }
    }

    @Test
    public void writeExceptionAfterCacheKeepsProtection() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.throwOnWrite = true; // исключение после изменения кеша
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            b.throwOnWrite = false;
            Resolution r = await(store);
            assertEquals(Status.READY, r.status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    public void clearExceptionAfterCacheKeepsProtection() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.throwOnClear = true;
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            b.throwOnClear = false;
            assertEquals(Status.READY, await(store).status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        } finally {
            store.close();
        }
    }

    // --- регрессии 2Г.3Б.2 ---

    @Test
    public void confirmedClearExpectsEmptyNotOriginalSnapshot()
            throws Exception {
        // Воспроизведение №1: enc+plain+default копии; PLAIN очищен,
        // DEFAULT — кеш очищен, commit=false. Повтор раньше давал
        // SOURCE_CHANGED по сравнению PLAIN с непустым снимком.
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.seed(Role.DEFAULT, T_A, 5L, null);
        b.failClearOn.add(2); // DEFAULT
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
            assertEquals(T_A, b.disk.get(Role.DEFAULT).get("token").s);

            Resolution r = await(store);
            assertEquals(Status.READY, r.status);
            assertEquals(3, b.clearCalls); // DEFAULT реально повторён
            assertTrue(b.disk.get(Role.DEFAULT).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    public void secondAndThirdClearFailuresAndRepeat() throws Exception {
        // Осиротевшие ключи во всех трёх: очистка ENCRYPTED ок,
        // отказы PLAIN (шаг 2) и DEFAULT (повтор, шаг 4) → потом EMPTY.
        FakeBackend b = new FakeBackend();
        b.putRaw(Role.ENCRYPTED, "chat", V.num(1L));
        b.putRaw(Role.PLAIN, "chat", V.num(2L));
        b.putRaw(Role.DEFAULT, "chat", V.num(3L));
        b.failClearOn.add(2);
        b.failClearOn.add(4);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            assertTrue(b.disk.get(Role.ENCRYPTED).isEmpty());
            assertEquals(2L, (long) b.disk.get(Role.PLAIN).get("chat").n);
            assertEquals(Status.INCOMPLETE, await(store).status);
            assertEquals(Status.EMPTY, await(store).status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
            assertTrue(b.disk.get(Role.DEFAULT).isEmpty());
            assertEquals(5, b.clearCalls);
        } finally {
            store.close();
        }
    }

    @Test
    public void newCredsInConfirmedClearedSourceBlock() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.seed(Role.DEFAULT, T_A, 5L, null);
        b.failClearOn.add(2);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            // Новые реквизиты в уже очищенном PLAIN — не удалять.
            b.cache.get(Role.PLAIN).put("token", V.str(T_B));
            b.disk.get(Role.PLAIN).put("token", V.str(T_B));
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Pending.SOURCE_CHANGED, r.pending);
            assertEquals(T_B, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            store.close();
        }
    }

    @Test
    public void unavailableSourceIsNotEmptyBeforeClear() throws Exception {
        // Воспроизведение №2: snapshot(PLAIN)=UNAVAILABLE перед очисткой —
        // remove не вызывается, операция не завершается READY.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failReadOn.add(4); // 3 снимка аудита + перепроверка PLAIN
        CredentialStore store = new CredentialStore(b);
        try {
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SOURCE_UNAVAILABLE, r.reason);
            assertEquals(Pending.UNREADABLE, r.pending);
            assertEquals(0, b.clearCalls);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);

            Resolution r2 = await(store); // источник снова читаем
            assertEquals(Status.READY, r2.status);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    public void corruptSnapshotBlocksResumeAfterFailedClear()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            b.throwOnSnapshot = true; // исключение чтения при resume
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SOURCE_CORRUPT, r.reason);
            b.throwOnSnapshot = false;
            assertEquals(Status.READY, await(store).status);
        } finally {
            store.close();
        }
    }

    @Test
    public void unreadableEncryptedBlocksRewrite() throws Exception {
        // Нельзя повторять запись поверх назначения, которое не читается.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            b.readState.put(Role.ENCRYPTED, Read.UNAVAILABLE);
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(1, b.writeCalls); // старая запись не повторена
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
            b.readState.put(Role.ENCRYPTED, Read.READABLE);
            assertEquals(Status.READY, await(store).status);
            assertEquals(2, b.writeCalls);
        } finally {
            store.close();
        }
    }

    // --- передача worker без параллельной работы ---

    @Test
    public void workerHandoverHeldCommitTrue() throws Exception {
        // Воспроизведение №3: удержанный commit, close/open handle.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.putStarted = new java.util.concurrent.CountDownLatch(1);
        b.putGate = new java.util.concurrent.CountDownLatch(1);
        CredentialStore s1 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f1 = s1.resolveAsync();
        assertTrue(b.putStarted.await(10, TimeUnit.SECONDS));
        s1.close(); // последний handle закрыт — задача продолжается

        CredentialStore s2 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f2 = s2.resolveAsync();
        // До освобождения записи — ни одного нового put/remove:
        assertEquals(1, b.writeCalls);
        assertEquals(0, b.clearCalls);
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        b.putGate.countDown();

        assertEquals(Status.READY, f1.get(10, TimeUnit.SECONDS).status);
        assertEquals(Status.READY, f2.get(10, TimeUnit.SECONDS).status);
        assertEquals(1, b.writeCalls); // подтверждённый commit не повторяется
        assertEquals(1, b.clearCalls);
        s2.close();
    }

    @Test
    public void workerHandoverHeldCommitFalse() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        b.putStarted = new java.util.concurrent.CountDownLatch(1);
        b.putGate = new java.util.concurrent.CountDownLatch(1);
        CredentialStore s1 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f1 = s1.resolveAsync();
        assertTrue(b.putStarted.await(10, TimeUnit.SECONDS));
        s1.close();
        CredentialStore s2 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f2 = s2.resolveAsync();
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        b.putGate.countDown();

        // Отказ назначения: исходник на диске до подтверждённого повтора.
        assertEquals(Status.INCOMPLETE, f1.get(10, TimeUnit.SECONDS).status);
        assertEquals(Status.READY, f2.get(10, TimeUnit.SECONDS).status);
        assertEquals(2, b.writeCalls); // реальный повтор записи
        assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        s2.close();
    }

    @Test
    public void workerHandoverHeldCommitThrows() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.throwWriteOn.add(1); // исключение после изменения кеша, один раз
        b.putStarted = new java.util.concurrent.CountDownLatch(1);
        b.putGate = new java.util.concurrent.CountDownLatch(1);
        CredentialStore s1 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f1 = s1.resolveAsync();
        assertTrue(b.putStarted.await(10, TimeUnit.SECONDS));
        s1.close();
        CredentialStore s2 = new CredentialStore(b);
        java.util.concurrent.Future<Resolution> f2 = s2.resolveAsync();
        b.putGate.countDown();
        assertEquals(Status.INCOMPLETE, f1.get(10, TimeUnit.SECONDS).status);
        assertEquals(Status.READY, f2.get(10, TimeUnit.SECONDS).status);
        s2.close();
    }

    @Test
    public void closeOpenCyclesStaySerialized() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        for (int i = 0; i < 3; i++) {
            CredentialStore s = new CredentialStore(b);
            Resolution r = s.resolveAsync().get(10, TimeUnit.SECONDS);
            s.close();
            if (i == 0) {
                assertEquals(Status.READY, r.status);
            }
        }
        assertEquals(1, b.writeCalls);
        assertEquals(1, b.clearCalls);
    }

    @Test
    public void resolveAfterCloseIsRejected() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        CredentialStore s = new CredentialStore(b);
        await(s);
        s.close();
        try {
            s.resolveAsync();
            fail("resolveAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
            // отклонено до постановки задачи
        }
    }

    @Test
    public void closeIsIdempotentAndThreadSafe() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        s.close();
        s.close(); // повторный close — без эффекта
        Thread t = new Thread(s::close);
        t.start();
        t.join(5000);
        // handle закрыт: новые задачи отклоняются
        try {
            s.resolveAsync();
            fail("ожидался отказ после close");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void secretsAbsentFromDiagnostics() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_B, 6L, null);
        Resolution r = await(new CredentialStore(b));
        String hay = r.toString() + r.reason.name() + r.status.name();
        assertFalse(hay.contains(T_A));
        assertFalse(hay.contains(T_B));
    }
}
