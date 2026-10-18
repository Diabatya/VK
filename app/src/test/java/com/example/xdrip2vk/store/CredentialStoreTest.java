package com.example.xdrip2vk.store;

import static org.junit.Assert.*;

import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.Fingerprint;
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
        boolean throwOnWrite, throwOnClear, throwOnSnapshot, throwOnDelete;
        boolean throwOnServiceState; // ошибка чтения маркера
        int removeTrigger = -1;      // номер вызова remove для onNthRemove
        Runnable onNthRemove;        // мутация после commit очистки
        int putTrigger = -1;         // номер вызова put для onNthPut
        Runnable onNthPut;           // мутация после успешного put commit
        int deleteTrigger = -1;      // номер вызова delete для onNthDelete
        Runnable onNthDelete;        // мутация после успешного delete commit
        final java.util.Set<Integer> throwWriteOn = new java.util.HashSet<>();
        final java.util.Set<Integer> failClearOn = new java.util.HashSet<>();
        final java.util.Set<Integer> failDeleteOn = new java.util.HashSet<>();
        int deleteCalls;
        final java.util.Set<Integer> failReadOn = new java.util.HashSet<>();
        int writeCalls, clearCalls;
        final AtomicInteger snapshots = new AtomicInteger();
        int snapshotTrigger = -1;   // номер вызова snapshot для onNthSnapshot
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
            if (onNthSnapshot != null && n == snapshotTrigger) {
                onNthSnapshot.run(); // изменение до перепроверки/проверки
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
        public ServiceState serviceState(Role role) {
            if (throwOnServiceState) {
                throw new RuntimeException("service state read failed");
            }
            V v = cache.get(role).get("meta");
            if (v == null) {
                return ServiceState.ABSENT;
            }
            if (v.wrongType || v.s == null) {
                return ServiceState.INVALID;
            }
            if (CredentialBackend.STATE_ACTIVE.equals(v.s)) {
                return ServiceState.ACTIVE;
            }
            if (CredentialBackend.STATE_DELETED.equals(v.s)) {
                return ServiceState.DELETED;
            }
            return ServiceState.INVALID;
        }

        @Override
        public boolean putCredentials(String token, long chatId,
                                      long alertChatId,
                                      String serviceState) {
            writeCalls++;
            cache.get(Role.ENCRYPTED).put("token", V.str(token));
            cache.get(Role.ENCRYPTED).put("chat", V.num(chatId));
            cache.get(Role.ENCRYPTED).put("alert", V.num(alertChatId));
            cache.get(Role.ENCRYPTED).put("meta",
                    V.str(CredentialBackend.serviceStatePart(serviceState)));
            String fm = CredentialBackend.fieldMetaPart(serviceState);
            if (fm.isEmpty()) {
                cache.get(Role.ENCRYPTED).remove("fmeta");
            } else {
                cache.get(Role.ENCRYPTED).put("fmeta", V.str(fm));
            }
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
            if (onNthPut != null && writeCalls == putTrigger) {
                onNthPut.run(); // мутация между commit и finish
            }
            return true;
        }

        @Override
        public String credentialMeta(Role role) {
            V v = cache.get(role).get("fmeta");
            return v == null || v.s == null ? "" : v.s;
        }

        @Override
        public boolean deleteCredentials() {
            deleteCalls++;
            Map<String, V> c = cache.get(Role.ENCRYPTED);
            c.remove("token");
            c.remove("chat");
            c.remove("alert");
            c.put("meta", V.str(CredentialBackend.STATE_DELETED));
            if (throwOnDelete) {
                throw new RuntimeException("delete failed after cache");
            }
            if (failDeleteOn.contains(deleteCalls)) {
                return false; // кеш «удалён», диск — нет
            }
            Map<String, V> d = disk.get(Role.ENCRYPTED);
            d.remove("token");
            d.remove("chat");
            d.remove("alert");
            d.put("meta", V.str(CredentialBackend.STATE_DELETED));
            if (onNthDelete != null && deleteCalls == deleteTrigger) {
                onNthDelete.run(); // мутация между commit и проверкой
            }
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
            if (onNthRemove != null && clearCalls == removeTrigger) {
                onNthRemove.run(); // мутация между очисткой и finish
            }
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
        b.snapshotTrigger = 4; // перепроверка PLAIN в clearOnce
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

    // --- регрессии 2Г.3Б.3: подтверждённое назначение перед очисткой ---

    @Test
    public void vanishedDestinationKeepsLegacyCopy() throws Exception {
        // Точное воспроизведение: запись ок, очистка plain commit=false,
        // затем encrypted исчез из кеша и диска. Удалять последнюю копию
        // plain нельзя.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore store = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(store).status);
            // Назначение исчезло из кеша и диска.
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();

            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
            assertEquals(1, b.clearCalls); // повторный remove не выполнялся
        } finally {
            store.close();
        }
    }

    @Test
    public void destinationVanishedBeforeFirstClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.snapshotTrigger = 5; // проверка назначения в clearOnce
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
        };
        CredentialStore store = new CredentialStore(b);
        try {
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
            assertEquals(0, b.clearCalls);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            store.close();
        }
    }

    @Test
    public void destinationChangedTokenBlocksClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.snapshotTrigger = 5;
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).put("token", V.str(T_B));
            b.disk.get(Role.ENCRYPTED).put("token", V.str(T_B));
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
        assertEquals(0, b.clearCalls);
        assertEquals(T_B, b.disk.get(Role.ENCRYPTED).get("token").s);
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
    }

    @Test
    public void destinationChangedChatIdBlocksClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.snapshotTrigger = 5;
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).put("chat", V.num(9L));
            b.disk.get(Role.ENCRYPTED).put("chat", V.num(9L));
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
        assertEquals(0, b.clearCalls);
    }

    @Test
    public void destinationChangedAlertBlocksClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, -2L);
        b.snapshotTrigger = 5;
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).put("alert", V.num(-7L));
            b.disk.get(Role.ENCRYPTED).put("alert", V.num(-7L));
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
        assertEquals(0, b.clearCalls);
    }

    @Test
    public void destinationUnreadableBlocksClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failReadOn.add(5); // снимок назначения перед очисткой
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.SOURCE_UNAVAILABLE, r.reason);
        assertEquals(Pending.UNREADABLE, r.pending);
        assertEquals(0, b.clearCalls);
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
    }

    @Test
    public void destinationChangedBetweenPlainAndDefaultClear()
            throws Exception {
        // Копии в трёх источниках: PLAIN очищен, перед очисткой DEFAULT
        // назначение исчезло — default не удалён.
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.seed(Role.DEFAULT, T_A, 5L, null);
        // clearOnce(DEFAULT): снимок default=#6, проверка назначения=#7
        b.snapshotTrigger = 7;
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
        assertTrue(b.disk.get(Role.PLAIN).isEmpty());      // уже подтверждено
        assertEquals(T_A, b.disk.get(Role.DEFAULT).get("token").s); // цел
    }

    @Test
    public void alreadyEncryptedVanishedBlocksClear() throws Exception {
        // Дедупликация без новой записи: исчезнувшая копия не основание.
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.snapshotTrigger = 5; // проверка назначения в clearOnce
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
        };
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
        assertEquals(0, b.clearCalls);
        assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
    }

    @Test
    public void alreadyEncryptedUnreadableBlocksClear() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failReadOn.add(5); // проверка назначения недоступна
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.INCOMPLETE, r.status);
        assertEquals(Pending.UNREADABLE, r.pending);
        assertEquals(0, b.clearCalls);
    }

    @Test
    public void differentValidTokenBeforeFinishIsNotReady() throws Exception {
        // После успешной записи+очистки перед finish появился другой
        // корректный encrypted-токен: READY с ним не возвращаем.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.snapshotTrigger = 6; // снимок enc в finish
        b.onNthSnapshot = () -> {
            b.cache.get(Role.ENCRYPTED).put("token", V.str(T_B));
            b.disk.get(Role.ENCRYPTED).put("token", V.str(T_B));
        };
        CredentialStore store = new CredentialStore(b);
        try {
            Resolution r = await(store);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.DESTINATION_MISMATCH, r.reason);
            assertFalse(r.sendPermitted());
            assertNotEquals(T_B, r.token);
        } finally {
            store.close();
        }
    }

    @Test
    public void unchangedDestinationCompletesMigration() throws Exception {
        // Назначение подтверждено и неизменно — операция завершается.
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        Resolution r = await(new CredentialStore(b));
        assertEquals(Status.READY, r.status);
        assertEquals(T_A, r.token);
        assertTrue(b.disk.get(Role.PLAIN).isEmpty());
    }

    // --- 2Г.3В.1: сохранение и удаление ---

    @Test
    public void savePersistsAndRecovers() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.saveAsync(T_A, 5L, -2L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            assertEquals(T_A, r.token);
            assertEquals(5L, r.chatId);
            assertEquals(-2L, r.alertChatId);
            assertEquals(1, b.writeCalls);
            // Новый процесс читает диск — реквизиты и состояние целы.
            FakeBackend b2 = b.reloadFromDisk();
            CredentialStore s2 = new CredentialStore(b2);
            Resolution r2 = await(s2);
            assertEquals(Status.READY, r2.status);
            assertEquals(T_A, r2.token);
            s2.close();
        } finally {
            s.close();
        }
    }

    @Test
    public void saveCommitFalseKeepsProtectionAcrossHandles()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.failWrites = 1;
        CredentialStore s1 = new CredentialStore(b);
        Resolution r1 = s1.saveAsync(T_A, 5L, 0L).get(10,
                TimeUnit.SECONDS);
        assertEquals(Status.INCOMPLETE, r1.status);
        s1.close();
        // Другой handle: грязный кеш не публикуется как READY.
        CredentialStore s2 = new CredentialStore(b);
        try {
            Resolution r2 = await(s2);
            assertEquals(Status.READY, r2.status);
            assertEquals(2, b.writeCalls); // реальный повтор записи
        } finally {
            s2.close();
        }
    }

    @Test
    public void emptyTokenSaveIsRejected() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.saveAsync("   ", 5L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.INVALID_INPUT, r.reason);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    @Test
    public void saveBlockedInConflict() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_B, 5L, null);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.saveAsync(T_A, 5L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    @Test
    public void deleteWritesMarkerAndBlocksLegacyResurrection()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.seed(Role.PLAIN, T_B, 6L, null); // legacy-остаток
        CredentialStore s = new CredentialStore(b);
        try {
            // Конфликт токенов: delete сначала заблокирован аудитом.
            Resolution r0 = s.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r0.status);
        } finally {
            s.close();
        }

        FakeBackend c = new FakeBackend();
        c.seed(Role.ENCRYPTED, T_A, 5L, null);
        c.seed(Role.PLAIN, T_B, 6L, null);
        c.cache.get(Role.PLAIN).clear(); // сценарий «удаление + legacy»
        c.disk.get(Role.PLAIN).clear();
        CredentialStore s2 = new CredentialStore(c);
        try {
            Resolution r = s2.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.DELETED, r.status);
            // Оставшийся legacy-токен в plain: миграции и отправки нет.
            c.cache.get(Role.PLAIN).put("token", V.str(T_B));
            c.disk.get(Role.PLAIN).put("token", V.str(T_B));
            Resolution r2 = await(s2);
            assertEquals(Status.DELETED, r2.status);
            assertFalse(r2.sendPermitted());
            assertEquals(0, c.writeCalls); // legacy не тронут, не мигрирован
        } finally {
            s2.close();
        }
    }

    @Test
    public void deleteFailureRetriedAndRecoveredFromDisk()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.failDeleteOn.add(1);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Pending.DELETE, r.pending);
            // Диск не изменён — «удалено» не подтверждено.
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
            Resolution r2 = await(s); // реальный повтор удаления
            assertEquals(Status.DELETED, r2.status);
            assertEquals(2, b.deleteCalls);
        } finally {
            s.close();
        }
        // Новый процесс: восстановление из disk показывает удаление.
        FakeBackend b2 = b.reloadFromDisk();
        Resolution r3 = await(new CredentialStore(b2));
        assertEquals(Status.DELETED, r3.status);
    }

    @Test
    public void saveAfterDeleteSucceedsAtomically() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            assertEquals(T_B, r.token);
            assertEquals(CredentialBackend.STATE_ACTIVE,
                    b.disk.get(Role.ENCRYPTED).get("meta").s);
        } finally {
            s.close();
        }
    }

    @Test
    public void saveAfterDeleteFailureKeepsDeletedState() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.failWrites = 1;
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r.status);
            // Новый процесс: сохранение не подтверждено — состояние
            // удаления остаётся, T_B активным не считается.
            FakeBackend b2 = b.reloadFromDisk();
            Resolution r2 = await(new CredentialStore(b2));
            assertEquals(Status.DELETED, r2.status);
            assertFalse(r2.sendPermitted());
        } finally {
            s.close();
        }
    }

    @Test
    public void invalidServiceStateBlocksAll() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.cache.get(Role.ENCRYPTED).put("meta", V.str("v9:weird"));
        b.disk.get(Role.ENCRYPTED).put("meta", V.str("v9:weird"));
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = await(s);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            Resolution r2 = s.saveAsync(T_B, 1L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r2.status);
            Resolution r3 = s.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r3.status);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    @Test
    public void commandsSerializeWithResolve() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s1 = new CredentialStore(b);
        CredentialStore s2 = new CredentialStore(b);
        try {
            java.util.concurrent.Future<Resolution> f1 =
                    s1.saveAsync(T_A, 5L, 0L);
            java.util.concurrent.Future<Resolution> f2 = s2.resolveAsync();
            assertEquals(Status.READY, f1.get(10, TimeUnit.SECONDS).status);
            assertEquals(Status.READY, f2.get(10, TimeUnit.SECONDS).status);
            assertEquals(1, b.writeCalls);
        } finally {
            s1.close();
            s2.close();
        }
    }

    @Test
    public void pendingMigrationNotBypassedBySave() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failClearOn.add(1);
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(s).status);
            // Команда записи не обходит незавершённую миграцию.
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(1, b.writeCalls); // только запись миграции
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
            // После достройки миграции сохранение разрешено.
            assertEquals(Status.READY, await(s).status);
            Resolution r2 = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.READY, r2.status);
            assertEquals(T_B, r2.token);
        } finally {
            s.close();
        }
    }

    @Test
    public void commandsAfterCloseRejected() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        s.close();
        try {
            s.saveAsync(T_A, 1L, 0L);
            fail("saveAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
        }
        try {
            s.deleteAsync();
            fail("deleteAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
        }
    }

    // --- 2Г.3В.1.1: маркер — часть состояния операции ---

    /** A: INVALID-маркер во время pending SAVE блокирует повторный put. */
    @Test
    public void invalidMarkerDuringPendingSaveBlocksRetry()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE,
                    s.saveAsync(T_A, 5L, 0L).get(10, TimeUnit.SECONDS)
                            .status);
            // Маркер стал неизвестным/битым после отказа commit.
            b.putRaw(Role.ENCRYPTED, "meta", V.str("v9:weird"));
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            assertFalse(r.sendPermitted());
            assertEquals(writes, b.writeCalls); // второго put нет
            // Операция не исчезла: после восстановления — реальный повтор.
            b.cache.get(Role.ENCRYPTED)
                    .put("meta", V.str(CredentialBackend.STATE_ACTIVE));
            assertEquals(Status.READY, await(s).status);
            assertEquals(writes + 1, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** INVALID-маркер во время pending MIGRATION: повторной записи нет. */
    @Test
    public void invalidMarkerDuringPendingMigrationBlocks()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(s).status);
            b.putRaw(Role.ENCRYPTED, "meta", V.str("v2:x"));
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            assertFalse(r.sendPermitted());
            assertEquals(writes, b.writeCalls);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            s.close();
        }
    }

    /** INVALID-маркер во время pending DELETE: повторного delete нет. */
    @Test
    public void invalidMarkerDuringPendingDeleteBlocks() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.failDeleteOn.add(1);
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.putRaw(Role.ENCRYPTED, "meta", V.str("oops"));
            int deletes = b.deleteCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            assertEquals(deletes, b.deleteCalls);
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        } finally {
            s.close();
        }
    }

    /** Ошибка чтения маркера при resume — блок, без записи. */
    @Test
    public void serviceStateReadErrorBlocksResume() throws Exception {
        FakeBackend b = new FakeBackend();
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE,
                    s.saveAsync(T_A, 5L, 0L).get(10, TimeUnit.SECONDS)
                            .status);
            b.throwOnServiceState = true;
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            assertEquals(writes, b.writeCalls);
            b.throwOnServiceState = false;
            assertEquals(Status.READY, await(s).status); // реальный повтор
            assertEquals(writes + 1, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** Неизвестная версия маркера перед finish не даёт опубликовать итог. */
    @Test
    public void unknownMarkerVersionBeforeFinishBlocks() throws Exception {
        FakeBackend b = new FakeBackend();
        b.putRaw(Role.PLAIN, "chat", V.num(5L)); // осиротевший адресат
        b.removeTrigger = 1;
        b.onNthRemove = () -> b.putRaw(Role.ENCRYPTED, "meta",
                V.str("v2:x")); // маркер испортился между очисткой и finish
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
            assertFalse(r.sendPermitted());
            // После восстановления маркера миграция-очистка достраивается.
            b.cache.get(Role.ENCRYPTED).remove("meta");
            b.disk.get(Role.ENCRYPTED).remove("meta");
            assertEquals(Status.EMPTY, await(s).status);
        } finally {
            s.close();
        }
    }

    /** B: DELETED + повреждённый encrypted — сохранение заблокировано. */
    @Test
    public void corruptEncryptedBlocksSaveInDeletedState()
            throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.readState.put(Role.ENCRYPTED, Read.CORRUPT);
            int writes = b.writeCalls;
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.SOURCE_CORRUPT, r.reason);
            assertFalse(r.sendPermitted());
            assertEquals(writes, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** B: DELETED + недоступный encrypted — сохранение заблокировано. */
    @Test
    public void unavailableEncryptedBlocksSaveInDeletedState()
            throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.readState.put(Role.ENCRYPTED, Read.UNAVAILABLE);
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.SOURCE_UNAVAILABLE, r.reason);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** B: DELETED + неверный тип поля encrypted — сохранение заблокировано. */
    @Test
    public void wrongTypeEncryptedBlocksSaveInDeletedState()
            throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.putRaw(Role.ENCRYPTED, "chat", V.str("not-a-number"));
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.SOURCE_CORRUPT, r.reason);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** DELETED + непустой encrypted — несогласованность, не перезаписывать. */
    @Test
    public void deletedWithEncryptedCredsBlocksSave() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            // Посторонняя запись реквизитов после удаления.
            b.putRaw(Role.ENCRYPTED, "token", V.str(T_A));
            Resolution r = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.SERVICE_STATE_MISMATCH, r.reason);
            assertEquals(0, b.writeCalls);
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
        } finally {
            s.close();
        }
    }

    /** DELETED + legacy: resolve без миграции, save без записи. */
    @Test
    public void deletedWithLegacyBlocksSaveAndMigration()
            throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.seed(Role.PLAIN, T_A, 5L, null); // «воскресший» legacy-токен
            Resolution r = await(s);
            assertEquals(Status.DELETED, r.status);
            assertFalse(r.sendPermitted());
            assertEquals(0, b.writeCalls);   // миграции нет
            assertEquals(0, b.clearCalls);   // legacy не тронут
            Resolution r2 = s.saveAsync(T_B, 9L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r2.status);
            assertEquals(Reason.SERVICE_STATE_MISMATCH, r2.reason);
            assertEquals(0, b.writeCalls);
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
        } finally {
            s.close();
        }
    }

    /** C: новое DELETED во время pending миграции не снимается старым планом. */
    @Test
    public void foreignDeleteBlocksPendingMigration() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE, await(s).status);
            // Подтверждённое удаление: enc пуст, маркер на диске.
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
            b.putRaw(Role.ENCRYPTED, "meta",
                    V.str(CredentialBackend.STATE_DELETED));
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_CHANGED, r.reason);
            assertFalse(r.sendPermitted());
            assertEquals(writes, b.writeCalls); // старый put не повторён
            assertEquals(0, b.clearCalls);    // legacy не очищен
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
            assertEquals(CredentialBackend.STATE_DELETED,
                    b.disk.get(Role.ENCRYPTED).get("meta").s);
        } finally {
            s.close();
        }
    }

    /** Новое DELETED во время pending SAVE не снимается старым сохранением. */
    @Test
    public void foreignDeleteBlocksPendingSave() throws Exception {
        FakeBackend b = new FakeBackend();
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE,
                    s.saveAsync(T_A, 5L, 0L).get(10, TimeUnit.SECONDS)
                            .status);
            // Постороннее подтверждённое удаление: диск без реквизитов,
            // маркер DELETED; кеш имитирует то же состояние.
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
            b.putRaw(Role.ENCRYPTED, "meta",
                    V.str(CredentialBackend.STATE_DELETED));
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(Reason.SERVICE_STATE_CHANGED, r.reason);
            assertEquals(writes, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** Отказ delete с грязным маркером: DELETED не публикуется до commit. */
    @Test
    public void deleteDirtyMarkerNotPublishedUntilCommit()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.failDeleteOn.add(1);
        CredentialStore s = new CredentialStore(b);
        // Другой backend того же storageId — общие диск/кеш, свой счётчик.
        FakeBackend b2 = b.sameProcessStorage();
        b2.failDeleteOn.add(1); // первый commit нового backend тоже false
        CredentialStore s2 = new CredentialStore(b2);
        try {
            assertEquals(Status.INCOMPLETE,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            // Грязный маркер DELETED в общем кеше — не результат.
            Resolution r = await(s2);
            assertEquals(Status.INCOMPLETE, r.status);
            assertEquals(1, b2.deleteCalls); // реальный повторный commit
            assertEquals(T_A, b.disk.get(Role.ENCRYPTED).get("token").s);
            Resolution r2 = await(s2);
            assertEquals(Status.DELETED, r2.status);
            assertEquals(2, b2.deleteCalls);
        } finally {
            s.close();
            s2.close();
        }
    }

    /** Новый handle того же storageId не обходит проверку маркера. */
    @Test
    public void newHandleDoesNotBypassMarkerCheck() throws Exception {
        FakeBackend b = new FakeBackend();
        b.failWrites = 1;
        CredentialStore s1 = new CredentialStore(b);
        try {
            assertEquals(Status.INCOMPLETE,
                    s1.saveAsync(T_A, 5L, 0L).get(10, TimeUnit.SECONDS)
                            .status);
            b.putRaw(Role.ENCRYPTED, "meta", V.str("v9:x"));
            int writes = b.writeCalls;
            // Другой backend того же storageId видит общий долг и маркер.
            CredentialStore s2 =
                    new CredentialStore(b.sameProcessStorage());
            try {
                Resolution r = await(s2);
                assertEquals(Status.INCOMPLETE, r.status);
                assertEquals(Reason.SERVICE_STATE_INVALID, r.reason);
                assertEquals(writes, b.writeCalls);
            } finally {
                s2.close();
            }
        } finally {
            s1.close();
        }
    }

    /** Отказ save после DELETED: повтор — реальная запись, затем READY. */
    @Test
    public void saveAfterDeleteRetryIsReal() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            b.failWrites = 1;
            assertEquals(Status.INCOMPLETE,
                    s.saveAsync(T_B, 9L, 0L).get(10, TimeUnit.SECONDS)
                            .status);
            int writes = b.writeCalls;
            Resolution r = await(s);
            assertEquals(Status.READY, r.status);
            assertEquals(T_B, r.token);
            assertEquals(writes + 1, b.writeCalls);
            assertEquals(CredentialBackend.STATE_ACTIVE,
                    b.disk.get(Role.ENCRYPTED).get("meta").s);
        } finally {
            s.close();
        }
    }

    // --- 2Г.3В.1.2: фазы «не подтверждено» / «подтверждено» ---

    /**
     * Точное воспроизведение: save из DELETED подтверждён, затем
     * постороннее удаление. Повторной записи старым планом быть не
     * должно, состояние удаления сохраняется.
     */
    @Test
    public void foreignDeleteAfterConfirmedSaveNotReverted()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.putRaw(Role.ENCRYPTED, "meta",
                V.str(CredentialBackend.STATE_DELETED));
        // После commit=true моделируем новое подтверждённое удаление.
        b.putTrigger = 1;
        b.onNthPut = () -> {
            b.cache.get(Role.ENCRYPTED).clear();
            b.disk.get(Role.ENCRYPTED).clear();
            b.putRaw(Role.ENCRYPTED, "meta",
                    V.str(CredentialBackend.STATE_DELETED));
        };
        CredentialStore s = new CredentialStore(b);
        CredentialStore s2 = new CredentialStore(b.sameProcessStorage());
        try {
            Resolution r1 = s.saveAsync(T_A, 5L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.SERVICE_STATE_CHANGED, r1.reason);
            int writes = b.writeCalls;
            // Подтверждённая операция не возвращается к исходной фазе:
            // повторного put нет ни у этого handle, ни у нового.
            Resolution r2 = await(s);
            assertEquals(Status.INCOMPLETE, r2.status);
            assertFalse(r2.sendPermitted());
            Resolution r3 = await(s2);
            assertEquals(Status.INCOMPLETE, r3.status);
            assertFalse(r3.sendPermitted());
            assertEquals(writes, b.writeCalls);
            assertEquals(1, b.writeCalls);
            assertNull(b.disk.get(Role.ENCRYPTED).get("token"));
            assertEquals(CredentialBackend.STATE_DELETED,
                    b.disk.get(Role.ENCRYPTED).get("meta").s);
        } finally {
            s.close();
            s2.close();
        }
    }

    /**
     * Подтверждённый SAVE + INVALID перед finish: после восстановления
     * ожидаемого ACTIVE завершение без второго put.
     */
    @Test
    public void confirmedSaveRecoversAfterInvalidWithoutRewrite()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.putTrigger = 1;
        b.onNthPut = () -> b.putRaw(Role.ENCRYPTED, "meta",
                V.str("v9:x"));
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r1 = s.saveAsync(T_A, 5L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r1.reason);
            b.putRaw(Role.ENCRYPTED, "meta",
                    V.str(CredentialBackend.STATE_ACTIVE));
            Resolution r2 = await(s);
            assertEquals(Status.READY, r2.status);
            assertEquals(T_A, r2.token);
            assertEquals(1, b.writeCalls); // повторной записи нет
        } finally {
            s.close();
        }
    }

    /**
     * Подтверждённый SAVE + другие реквизиты/маркер ACTIVE: блок без
     * перезаписи чужого содержимого.
     */
    @Test
    public void confirmedSaveWithForeignCredsNotOverwritten()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.putTrigger = 1;
        b.onNthPut = () -> b.putRaw(Role.ENCRYPTED, "token", V.str(T_B));
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r1 = s.saveAsync(T_A, 5L, 0L).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.DESTINATION_MISMATCH, r1.reason);
            Resolution r2 = await(s);
            assertEquals(Status.INCOMPLETE, r2.status);
            assertFalse(r2.sendPermitted());
            assertEquals(1, b.writeCalls);
            assertEquals(T_B, b.disk.get(Role.ENCRYPTED).get("token").s);
        } finally {
            s.close();
        }
    }

    /**
     * Подтверждённый DELETE + появившиеся реквизиты: повторного delete
     * нет, чужие реквизиты сохранены.
     */
    @Test
    public void confirmedDeleteNotRetriedForAppearedCreds()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.deleteTrigger = 1;
        b.onNthDelete = () -> b.putRaw(Role.ENCRYPTED, "token",
                V.str(T_B)); // посторонняя запись после удаления
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r1 = s.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.SERVICE_STATE_MISMATCH, r1.reason);
            Resolution r2 = await(s);
            assertEquals(Status.INCOMPLETE, r2.status);
            assertFalse(r2.sendPermitted());
            assertEquals(1, b.deleteCalls); // второго delete нет
            assertEquals(T_B, b.disk.get(Role.ENCRYPTED).get("token").s);
            assertEquals(CredentialBackend.STATE_DELETED,
                    b.disk.get(Role.ENCRYPTED).get("meta").s);
        } finally {
            s.close();
        }
    }

    /**
     * Подтверждённый DELETE + временная ошибка чтения маркера: после
     * восстановления корректный DELETED без второго delete.
     */
    @Test
    public void confirmedDeleteCompletesAfterReadError()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, null);
        b.deleteTrigger = 1;
        b.onNthDelete = () -> b.throwOnServiceState = true;
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r1 = s.deleteAsync().get(10, TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.SERVICE_STATE_INVALID, r1.reason);
            b.throwOnServiceState = false;
            Resolution r2 = await(s);
            assertEquals(Status.DELETED, r2.status);
            assertEquals(1, b.deleteCalls);
            assertNull(b.disk.get(Role.ENCRYPTED).get("token"));
        } finally {
            s.close();
        }
    }

    /**
     * Подтверждённая запись MIGRATION + возврат исходного ABSENT:
     * legacy не очищается, повторной записи нет.
     */
    @Test
    public void migrationInitialMarkerReturnBlocksClear()
            throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.PLAIN, T_A, 5L, null);
        b.putTrigger = 1;
        b.onNthPut = () -> {
            b.cache.get(Role.ENCRYPTED).remove("meta");
            b.disk.get(Role.ENCRYPTED).remove("meta");
        };
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r1 = await(s);
            assertEquals(Status.INCOMPLETE, r1.status);
            assertEquals(Reason.SERVICE_STATE_CHANGED, r1.reason);
            assertEquals(0, b.clearCalls); // legacy не очищен
            assertEquals(T_A, b.disk.get(Role.PLAIN).get("token").s);
            // Восстановление ожидаемого маркера — достройка без re-put.
            b.putRaw(Role.ENCRYPTED, "meta",
                    V.str(CredentialBackend.STATE_ACTIVE));
            Resolution r2 = await(s);
            assertEquals(Status.READY, r2.status);
            assertEquals(T_A, r2.token);
            assertEquals(1, b.writeCalls);
            assertTrue(b.disk.get(Role.PLAIN).isEmpty());
        } finally {
            s.close();
        }
    }

    // --- 2Г.3В.2: изменение отдельных реквизитов ---

    /** Изменение токена сохраняет актуальные адресаты. */
    @Test
    public void setTokenKeepsCurrentChatIds() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.setTokenAsync(T_B).get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            assertEquals(T_B, r.token);
            assertEquals(5L, r.chatId);
            assertEquals(-2L, r.alertChatId);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_B, d.get("token").s);
            assertEquals(5L, (long) d.get("chat").n);
            assertEquals(-2L, (long) d.get("alert").n);
            assertEquals(1, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** Изменение обычного адресата сохраняет токен и тревожный. */
    @Test
    public void setChatIdKeepsTokenAndAlert() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.setChatIdAsync(7L, Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_A, d.get("token").s);
            assertEquals(7L, (long) d.get("chat").n);
            assertEquals(-2L, (long) d.get("alert").n);
        } finally {
            s.close();
        }
    }

    /** Изменение тревожного сохраняет обычный; отрицательное значение ок. */
    @Test
    public void setAlertChatIdKeepsChatAndNegative() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.setAlertChatIdAsync(-9L, Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(5L, (long) d.get("chat").n);
            assertEquals(-9L, (long) d.get("alert").n);
        } finally {
            s.close();
        }
    }

    /** Сброс обоих адресатов — одна запись, токен сохранён. */
    @Test
    public void clearChatIdsSingleCommit() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.clearChatIdsAsync(Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r.status);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_A, d.get("token").s);
            assertEquals(0L, (long) d.get("chat").n);
            assertEquals(0L, (long) d.get("alert").n);
            assertEquals(1, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /**
     * Список загружен с T_A, затем сохранён T_B, затем выбран чат из
     * списка A: запись отклонена, данные B целы.
     */
    @Test
    public void staleTokenRejectsChatChoice() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            String fpA = Fingerprint.of(T_A);
            assertEquals(Status.READY,
                    s.setTokenAsync(T_B).get(10, TimeUnit.SECONDS).status);
            int writes = b.writeCalls;
            Resolution r = s.setChatIdAsync(9L, fpA).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.STALE_TOKEN, r.reason);
            assertFalse(r.sendPermitted());
            assertEquals(writes, b.writeCalls); // записи не было
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_B, d.get("token").s);
            assertEquals(5L, (long) d.get("chat").n);
        } finally {
            s.close();
        }
    }

    /** Устаревшая команда сброса не меняет чаты нового токена. */
    @Test
    public void staleClearDoesNotTouchNewTokenChats() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            String fpA = Fingerprint.of(T_A);
            assertEquals(Status.READY,
                    s.setTokenAsync(T_B).get(10, TimeUnit.SECONDS).status);
            Resolution r = s.clearChatIdsAsync(fpA).get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.BLOCKED, r.status);
            assertEquals(Reason.STALE_TOKEN, r.reason);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_B, d.get("token").s);
            assertEquals(5L, (long) d.get("chat").n);
            assertEquals(-2L, (long) d.get("alert").n);
        } finally {
            s.close();
        }
    }

    /**
     * Две команды разных handle, меняющие разные поля, сериализованы и
     * не теряют изменений друг друга.
     */
    @Test
    public void twoHandlesDifferentFieldsBothApplied() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s1 = new CredentialStore(b);
        // Второй backend того же storageId: общий диск/кеш, свои счётчики.
        FakeBackend b2 = b.sameProcessStorage();
        CredentialStore s2 = new CredentialStore(b2);
        try {
            String fp = Fingerprint.of(T_A);
            java.util.concurrent.Future<Resolution> f1 =
                    s1.setChatIdAsync(7L, fp);
            java.util.concurrent.Future<Resolution> f2 =
                    s2.setAlertChatIdAsync(-4L, fp);
            assertEquals(Status.READY, f1.get(10, TimeUnit.SECONDS).status);
            assertEquals(Status.READY, f2.get(10, TimeUnit.SECONDS).status);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_A, d.get("token").s);
            assertEquals(7L, (long) d.get("chat").n);
            assertEquals(-4L, (long) d.get("alert").n);
            assertEquals(2, b.writeCalls + b2.writeCalls);
        } finally {
            s1.close();
            s2.close();
        }
    }

    /**
     * Изменение токена и адресата в одной очереди выполняются по
     * порядку: результат второй команды видит итог первой.
     */
    @Test
    public void tokenAndChatCommandsRunInOrder() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            String fpA = Fingerprint.of(T_A);
            java.util.concurrent.Future<Resolution> f1 =
                    s.setTokenAsync(T_B);
            java.util.concurrent.Future<Resolution> f2 =
                    s.setChatIdAsync(9L, fpA); // устаревший fingerprint
            assertEquals(Status.READY, f1.get(10, TimeUnit.SECONDS).status);
            Resolution r2 = f2.get(10, TimeUnit.SECONDS);
            // Команда встала после смены токена: fpA устарел → отказ.
            assertEquals(Status.BLOCKED, r2.status);
            assertEquals(Reason.STALE_TOKEN, r2.reason);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_B, d.get("token").s);
            assertEquals(5L, (long) d.get("chat").n);
            // С актуальным fingerprint — разрешено.
            Resolution r3 = s.setChatIdAsync(9L, Fingerprint.of(T_B))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.READY, r3.status);
            assertEquals(9L, (long) b.disk.get(Role.ENCRYPTED)
                    .get("chat").n);
        } finally {
            s.close();
        }
    }

    /** EMPTY и DELETED не позволяют выбрать чат без токена. */
    @Test
    public void emptyOrDeletedCannotChooseChat() throws Exception {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.setChatIdAsync(9L, Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.EMPTY, r.status);
            assertFalse(r.sendPermitted());
            assertEquals(0, b.writeCalls);
            assertEquals(Status.DELETED,
                    s.deleteAsync().get(10, TimeUnit.SECONDS).status);
            Resolution r2 = s.setChatIdAsync(9L, Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.DELETED, r2.status);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** Пустой после trim токен выполняет подтверждённое удаление. */
    @Test
    public void emptyTokenPerformsConfirmedDelete() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        CredentialStore s = new CredentialStore(b);
        try {
            Resolution r = s.setTokenAsync("   ").get(10,
                    TimeUnit.SECONDS);
            assertEquals(Status.DELETED, r.status);
            assertFalse(r.sendPermitted());
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertNull(d.get("token"));
            assertNull(d.get("chat"));
            assertEquals(CredentialBackend.STATE_DELETED, d.get("meta").s);
            assertEquals(1, b.deleteCalls);
            assertEquals(0, b.writeCalls);
        } finally {
            s.close();
        }
    }

    /** commit=false изменения поля: грязный кеш не публикуется, повтор реален. */
    @Test
    public void fieldUpdateCommitFalseKeepsProtection() throws Exception {
        FakeBackend b = new FakeBackend();
        b.seed(Role.ENCRYPTED, T_A, 5L, -2L);
        b.failWrites = 1;
        CredentialStore s = new CredentialStore(b);
        FakeBackend b2 = b.sameProcessStorage();
        CredentialStore s2 = new CredentialStore(b2);
        try {
            Resolution r = s.setChatIdAsync(7L, Fingerprint.of(T_A))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Status.INCOMPLETE, r.status);
            // Грязный кеш (T_A,7) не подтверждён: другой handle не
            // публикует его, а реальный повтор доводит commit.
            Resolution r2 = await(s2);
            assertEquals(Status.READY, r2.status);
            assertEquals(2, b.writeCalls + b2.writeCalls);
            Map<String, V> d = b.disk.get(Role.ENCRYPTED);
            assertEquals(T_A, d.get("token").s);
            assertEquals(7L, (long) d.get("chat").n);
            assertEquals(-2L, (long) d.get("alert").n);
        } finally {
            s.close();
            s2.close();
        }
    }

    /** После close команды изменения полей отклоняются. */
    @Test
    public void closedHandleRejectsFieldCommands() {
        FakeBackend b = new FakeBackend();
        CredentialStore s = new CredentialStore(b);
        s.close();
        try {
            s.setTokenAsync(T_A);
            fail("setTokenAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
        }
        try {
            s.setChatIdAsync(1L, Fingerprint.of(T_A));
            fail("setChatIdAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
        }
        try {
            s.setAlertChatIdAsync(1L, Fingerprint.of(T_A));
            fail("setAlertChatIdAsync после close должен отклоняться");
        } catch (IllegalStateException expected) {
        }
        try {
            s.clearChatIdsAsync(Fingerprint.of(T_A));
            fail("clearChatIdsAsync после close должен отклоняться");
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
