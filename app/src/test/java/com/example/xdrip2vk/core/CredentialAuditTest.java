package com.example.xdrip2vk.core;

import static org.junit.Assert.*;

import com.example.xdrip2vk.core.CredentialAudit.Entry;
import com.example.xdrip2vk.core.CredentialAudit.Outcome;
import com.example.xdrip2vk.core.CredentialAudit.Plan;
import com.example.xdrip2vk.core.CredentialAudit.Read;
import com.example.xdrip2vk.core.CredentialAudit.Reason;
import com.example.xdrip2vk.core.CredentialAudit.Role;
import com.example.xdrip2vk.core.CredentialAudit.Source;
import com.example.xdrip2vk.core.CredentialAudit.Verdict;

import org.junit.Test;

/**
 * Полная таблица входов CredentialAudit: три источника
 * (ENCRYPTED=secure_tokens, PLAIN=secure_tokens_plain, DEFAULT=legacy),
 * вердикт и код причины. Ядро чистое — без Keystore/файлов/диска;
 * тесты не проверяют реальный Keystore и запись.
 */
public class CredentialAuditTest {

    private static final String T_A = "SYNTH_TKN_A_q1";
    private static final String T_B = "SYNTH_TKN_B_w2";

    private static Source ok(Role r) {
        return Source.of(r, Read.READABLE, Entry.absent(), Entry.absent(),
                Entry.absent());
    }

    private static Source creds(Role r, String token, Long chat,
                                Long alert) {
        return Source.of(r, Read.READABLE,
                token == null ? Entry.absent() : Entry.string(token),
                chat == null ? Entry.absent() : Entry.number(chat),
                alert == null ? Entry.absent() : Entry.number(alert));
    }

    // --- обязательные случаи ---

    @Test
    public void allReadableNoKeysIsEmpty() {
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED), ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.EMPTY, o.verdict);
        assertEquals(Reason.NO_CREDENTIALS, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void onlyEncryptedWithTokenIsReadySecured() {
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null), ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.READY_SECURED, o.verdict);
        assertEquals(Reason.UNIQUE_SECURED, o.reason);
        assertTrue(o.sendPermitted());
    }

    @Test
    public void onlyPlainIsMigrationRequired() {
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, -3L), ok(Role.DEFAULT));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.LEGACY_ONLY, o.reason);
        assertFalse(o.sendPermitted());
        Plan p = o.plan;
        assertNotNull(p);
        assertEquals(T_A, p.token);
        assertEquals(5L, p.chatId);
        assertEquals(-3L, p.alertChatId);
        assertEquals(java.util.Collections.singletonList(Role.PLAIN),
                p.clearSources);
        assertFalse(p.alreadyEncrypted);
    }

    @Test
    public void onlyDefaultIsMigrationRequired() {
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED), ok(Role.PLAIN),
                creds(Role.DEFAULT, T_A, 7L, null));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.LEGACY_ONLY, o.reason);
        assertEquals(java.util.Collections.singletonList(Role.DEFAULT),
                o.plan.clearSources);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void identicalCopiesAreMigrationRequired() {
        // Одинаковые реквизиты в encrypted+plain: перенос не нужен,
        // только подтверждённая очистка лишней копии.
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, 0L),
                creds(Role.PLAIN, T_A, 5L, 0L), ok(Role.DEFAULT));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.DUPLICATE_COPIES, o.reason);
        assertEquals(java.util.Collections.singletonList(Role.PLAIN),
                o.plan.clearSources);
        assertTrue(o.plan.alreadyEncrypted);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void identicalPlainAndDefaultCopiesAreMigration() {
        // Копии в plain+default при пустом encrypted: перенос в encrypted.
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, null),
                creds(Role.DEFAULT, T_A, 5L, null));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.DUPLICATE_COPIES, o.reason);
        assertEquals(2, o.plan.clearSources.size());
        assertFalse(o.plan.alreadyEncrypted);
    }

    @Test
    public void differentNonEmptyTokensIsConflict() {
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_B, 5L, null), ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.TOKEN_MISMATCH, o.reason);
        assertNull(o.plan);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void sameTokenDifferentChatIdIsConflict() {
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_A, 6L, null), ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.ADDRESS_MISMATCH, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void sameTokenDifferentAlertChatIdIsConflict() {
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, -2L),
                creds(Role.PLAIN, T_A, 5L, -4L), ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.ADDRESS_MISMATCH, o.reason);
    }

    @Test
    public void encryptedUnavailableWithLegacyDataIsUnavailable() {
        Outcome o = CredentialAudit.check(Source.unavailable(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, null), ok(Role.DEFAULT));
        assertEquals(Verdict.UNAVAILABLE, o.verdict);
        assertEquals(Reason.ENCRYPTED_UNAVAILABLE, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void anyCorruptSourceIsCorruptWithoutSubstitution() {
        // Повреждённый plain не подменяется валидным encrypted.
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                Source.corrupt(Role.PLAIN), ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertEquals(Reason.SOURCE_CORRUPT, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void wrongTypeValueIsCorruptNotAbsent() {
        // Ключ есть, тип неожиданный — не «отсутствует», а повреждение.
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.wrongType(), Entry.absent(), Entry.absent());
        Outcome o = CredentialAudit.check(enc, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertEquals(Reason.SOURCE_CORRUPT, o.reason);
    }

    @Test
    public void emptyStoredTokenVsNonEmptyIsConflict() {
        // Пустой сохранённый токен + непустой в другом источнике:
        // без маркера удаление не доказано.
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.string(""), Entry.absent(), Entry.absent());
        Outcome o = CredentialAudit.check(enc,
                creds(Role.PLAIN, T_A, 5L, null), ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.EMPTY_TOKEN_AMBIGUITY, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void tokenHereAddressesThereIsConflict() {
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, null, null),
                creds(Role.DEFAULT, null, 5L, null));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.SPLIT_CREDENTIALS, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void absentChatEqualsExplicitZero() {
        // Отдельное правило: отсутствующий адресат ≡ явно сохранённый 0.
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, null, null),
                creds(Role.PLAIN, T_A, 0L, 0L), ok(Role.DEFAULT));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.DUPLICATE_COPIES, o.reason);
    }

    @Test
    public void absentChatVsNonZeroChatIsConflict() {
        // absent(0) против явного ненулевого адресата — расхождение.
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_A, null, null), ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.ADDRESS_MISMATCH, o.reason);
    }

    @Test
    public void orphanAddressesWithoutTokenAreMigrationCleanup() {
        // Адресаты есть, токена нет нигде — только очистка, не EMPTY.
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                ok(Role.PLAIN), creds(Role.DEFAULT, null, 5L, null));
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(Reason.ORPHAN_ADDRESSES, o.reason);
        assertTrue("план без токена не даёт активного токена",
                o.plan.token.isEmpty());
        assertFalse(o.sendPermitted());
    }

    @Test
    public void legacyUnavailableBlocksDecision() {
        // Encrypted валиден, но plain недоступен — однозначность
        // «только в encrypted» не доказана.
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                Source.unavailable(Role.PLAIN), ok(Role.DEFAULT));
        assertEquals(Verdict.UNAVAILABLE, o.verdict);
        assertEquals(Reason.SOURCE_UNAVAILABLE, o.reason);
        assertFalse(o.sendPermitted());
    }

    // --- дополнительные проверки ---

    @Test
    public void repeatedCheckIsDeterministic() {
        Source e = creds(Role.ENCRYPTED, T_A, 5L, null);
        Source p = creds(Role.PLAIN, T_B, 5L, null);
        Source d = ok(Role.DEFAULT);
        Outcome a = CredentialAudit.check(e, p, d);
        Outcome b = CredentialAudit.check(e, p, d);
        assertEquals(a, b);
    }

    @Test
    public void resultIndependentOfSourceOrder() {
        // Алгоритм не выбирает «первого попавшегося»: симметричные
        // комбинации дают одинаковый вердикт вне зависимости от того,
        // какие роли заняты источниками-копиями.
        Outcome ab = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, null),
                creds(Role.DEFAULT, T_B, 5L, null));
        Outcome ba = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_B, 5L, null),
                creds(Role.DEFAULT, T_A, 5L, null));
        assertEquals(ab, ba);
        assertEquals(Verdict.CONFLICT, ab.verdict);
    }

    @Test
    public void snapshotsAreNotMutatedByCheck() {
        Source e = creds(Role.ENCRYPTED, T_A, 5L, -9L);
        Source p = creds(Role.PLAIN, T_B, 6L, null);
        Source d = Source.unavailable(Role.DEFAULT);
        CredentialAudit.check(e, p, d);
        assertEquals(T_A, e.token.string);
        assertEquals(5L, e.chatId.number);
        assertEquals(-9L, e.alertChatId.number);
        assertEquals(T_B, p.token.string);
        assertEquals(Read.UNAVAILABLE, d.read);
    }

    @Test
    public void largeLongAndNegativeAlertPreserved() {
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, Long.MAX_VALUE, -2000000001L),
                ok(Role.DEFAULT));
        assertEquals(Long.MAX_VALUE, o.plan.chatId);
        assertEquals(-2000000001L, o.plan.alertChatId);
    }

    @Test
    public void encryptedFlapDoesNotSelectPlainToken() {
        Source plain = creds(Role.PLAIN, T_A, 5L, null);
        Source def = ok(Role.DEFAULT);
        // READABLE(пустой) → UNAVAILABLE → READABLE: ни на одном шаге
        // plain-токен не становится разрешённым.
        assertFalse(CredentialAudit.check(ok(Role.ENCRYPTED), plain, def)
                .sendPermitted());
        assertFalse(CredentialAudit.check(
                Source.unavailable(Role.ENCRYPTED), plain, def)
                .sendPermitted());
        assertFalse(CredentialAudit.check(ok(Role.ENCRYPTED), plain, def)
                .sendPermitted());
    }

    // --- регрессии 2Г.3А.1: обход типов ---

    @Test
    public void regressionA_numericTokenNotReadySecured() {
        // Воспроизведение: числовой «токен» давал READY_SECURED.
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.number(123), Entry.number(777), Entry.absent());
        Outcome o = CredentialAudit.check(enc, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertEquals(Reason.SOURCE_CORRUPT, o.reason);
        assertFalse(o.sendPermitted());
        assertNull(o.plan);
    }

    @Test
    public void regressionB_stringAddressNotReadySecured() {
        // Воспроизведение: строковый «адресат» давал READY_SECURED.
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.string("SYNTH_TOKEN"), Entry.string("unexpected"),
                Entry.absent());
        Outcome o = CredentialAudit.check(enc, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertFalse(o.sendPermitted());
        assertNull(o.plan);
    }

    @Test
    public void emptyStringTypeInAddressIsCorrupt() {
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.string(T_A), Entry.number(5), Entry.string(""));
        Outcome o = CredentialAudit.check(enc, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void wrongTypeDoesNotBecomeEmptyTokenOrZeroAddress() {
        // Неправильный тип ≠ «нет токена» / «адресат 0»: не READY и не
        // тихое слияние — только CORRUPT.
        Source enc = Source.of(Role.ENCRYPTED, Read.READABLE,
                Entry.wrongType(), Entry.number(5), Entry.absent());
        Outcome o = CredentialAudit.check(enc, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CORRUPT, o.verdict);
        assertFalse(o.sendPermitted());
    }

    // --- структура входа и роли ---

    @Test
    public void nullSourceIsInvalidInput() {
        Outcome o = CredentialAudit.check(null, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Verdict.CONFLICT, o.verdict);
        assertEquals(Reason.INVALID_INPUT, o.reason);
        assertNull(o.plan);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void nullRoleIsInvalidInput() {
        Source bad = Source.of(null, Read.READABLE, Entry.absent(),
                Entry.absent(), Entry.absent());
        Outcome o = CredentialAudit.check(bad, ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Reason.INVALID_INPUT, o.reason);
    }

    @Test
    public void duplicatedRoleIsInvalidInput() {
        Outcome o = CredentialAudit.check(ok(Role.PLAIN), ok(Role.PLAIN),
                ok(Role.DEFAULT));
        assertEquals(Reason.INVALID_INPUT, o.reason);
        assertFalse(o.sendPermitted());
    }

    @Test
    public void roleDecidesSourceNotPosition() {
        // Воспроизведённая ошибка: результат зависел от позиции аргумента.
        Source enc = creds(Role.ENCRYPTED, T_A, 5L, null);
        Source unavail = Source.unavailable(Role.PLAIN);
        Source def = ok(Role.DEFAULT);
        Outcome a = CredentialAudit.check(enc, unavail, def);
        Outcome b = CredentialAudit.check(unavail, enc, def);
        assertEquals(a, b);
        assertEquals(Reason.SOURCE_UNAVAILABLE, a.reason);
    }

    @Test
    public void encryptedUnavailableMatchesRoleRegardlessOfPosition() {
        Source unavail = Source.unavailable(Role.ENCRYPTED);
        Source plain = creds(Role.PLAIN, T_A, 5L, null);
        Source def = ok(Role.DEFAULT);
        assertEquals(Reason.ENCRYPTED_UNAVAILABLE,
                CredentialAudit.check(unavail, plain, def).reason);
        assertEquals(Reason.ENCRYPTED_UNAVAILABLE,
                CredentialAudit.check(plain, unavail, def).reason);
        assertEquals(Reason.ENCRYPTED_UNAVAILABLE,
                CredentialAudit.check(plain, def, unavail).reason);
    }

    // --- шесть перестановок с полным сравнением результата ---

    private static void assertSameOutcome(Outcome a, Outcome b) {
        assertEquals(a.verdict, b.verdict);
        assertEquals(a.reason, b.reason);
        assertEquals(a.plan == null, b.plan == null);
        if (a.plan != null) {
            assertEquals(a.plan.token, b.plan.token);
            assertEquals(a.plan.chatId, b.plan.chatId);
            assertEquals(a.plan.alertChatId, b.plan.alertChatId);
            assertEquals(a.plan.alreadyEncrypted, b.plan.alreadyEncrypted);
            assertEquals(a.plan.clearSources, b.plan.clearSources);
        }
    }

    private static void checkAllPermutations(Source s1, Source s2, Source s3) {
        Outcome base = CredentialAudit.check(s1, s2, s3);
        assertSameOutcome(base, CredentialAudit.check(s1, s3, s2));
        assertSameOutcome(base, CredentialAudit.check(s2, s1, s3));
        assertSameOutcome(base, CredentialAudit.check(s2, s3, s1));
        assertSameOutcome(base, CredentialAudit.check(s3, s1, s2));
        assertSameOutcome(base, CredentialAudit.check(s3, s2, s1));
    }

    @Test
    public void allPermutationsAgree() {
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, null),
                ok(Role.PLAIN), ok(Role.DEFAULT));           // READY_SECURED
        checkAllPermutations(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, -1L),
                ok(Role.DEFAULT));                          // MIGRATION legacy
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, 0L),
                creds(Role.PLAIN, T_A, 5L, 0L),
                ok(Role.DEFAULT));                          // копии
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_B, 5L, null),
                ok(Role.DEFAULT));                          // токены
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_A, 6L, null),
                ok(Role.DEFAULT));                          // адресаты
        checkAllPermutations(Source.unavailable(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, null),
                ok(Role.DEFAULT));                          // enc UNAVAILABLE
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, null),
                Source.unavailable(Role.PLAIN),
                ok(Role.DEFAULT));                          // plain UNAVAILABLE
        checkAllPermutations(creds(Role.ENCRYPTED, T_A, 5L, null),
                Source.corrupt(Role.PLAIN),
                ok(Role.DEFAULT));                          // CORRUPT
    }

    @Test
    public void clearSourcesHasNoDuplicatesAndStableRoleOrder() {
        // Пустой токен и адресаты в одном источнике — роль не дублируется.
        Source def = Source.of(Role.DEFAULT, Read.READABLE,
                Entry.string(""), Entry.number(5), Entry.absent());
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                ok(Role.PLAIN), def);
        assertEquals(Verdict.MIGRATION_REQUIRED, o.verdict);
        assertEquals(java.util.Collections.singletonList(Role.DEFAULT),
                o.plan.clearSources);

        // Три одинаковые копии: канонический порядок PLAIN, DEFAULT.
        Outcome o2 = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_A, 5L, null),
                creds(Role.DEFAULT, T_A, 5L, null));
        assertEquals(java.util.Arrays.asList(Role.PLAIN, Role.DEFAULT),
                o2.plan.clearSources);
    }

    @Test
    public void secretsAbsentFromToString() {
        Source s = creds(Role.ENCRYPTED, T_A, 5L, null);
        Outcome o = CredentialAudit.check(ok(Role.ENCRYPTED),
                creds(Role.PLAIN, T_A, 5L, null), ok(Role.DEFAULT));
        String hay = s.toString() + o.toString() + o.plan.toString()
                + s.token.toString();
        assertFalse(hay.contains(T_A));
        assertFalse(hay.contains(T_B));
    }

    @Test
    public void conflictReasonCarriesNoValues() {
        Outcome o = CredentialAudit.check(
                creds(Role.ENCRYPTED, T_A, 5L, null),
                creds(Role.PLAIN, T_B, 6L, null), ok(Role.DEFAULT));
        assertFalse(o.toString().contains(T_A));
        assertFalse(o.toString().contains(T_B));
        assertFalse(o.reason.name().contains(T_A));
    }
}
