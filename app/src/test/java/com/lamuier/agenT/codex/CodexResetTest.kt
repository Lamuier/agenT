package com.lamuier.agenT.codex

import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CodexResetTest {
    private val now = Instant.parse("2026-10-07T03:00:00Z").epochSecond
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun quota(deadline: Long? = now + 1000, used: Double = 80.0, count: Int? = null,
        credits: List<CodexResetCredit>? = null, duration: Long = 18000) =
        CodexQuotaOverview("plus", CodexQuotaWindow(used, duration, null, deadline), null, emptyList(), count, "fixture",
            resetCredits = CodexResetCredits(count, credits))
    private fun event(id: String = "manual", at: Long = now, kind: CodexResetKind = CodexResetKind.ManualUse,
        source: CodexResetSource = CodexResetSource.Registered, count: Int = 1) =
        CodexResetEvent(id, kind, source, at, count)
    private fun baseline() = CodexResetTracker.observe(CodexResetLedger(), quota(count = 3), now)
    private fun assertRejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun firstSnapshotDoesNotInventHistoricalResets() {
        val ledger = baseline()
        assertTrue(ledger.events.isEmpty())
        assertEquals(now, ledger.startedAt)
        assertEquals(3, ledger.previous?.available)
    }
    @Test fun cachedAndOlderSnapshotsDoNotAlterLedger() {
        val ledger = baseline()
        assertEquals(ledger, CodexResetTracker.observe(ledger, quota(now + 18000).copy(fromCache = true), now + 1))
        assertEquals(ledger, CodexResetTracker.observe(ledger, quota(now + 18000), now))
        assertEquals(ledger, CodexResetTracker.observe(ledger, quota(now + 18000), now - 1))
    }
    @Test fun earlyWindowChangeIsUnclassifiedNotAnOfficialOrManualReset() {
        val ledger = CodexResetTracker.observe(baseline(), quota(now + 18000, used = 0.0), now + 30)
        val row = ledger.events.single()
        assertEquals(CodexResetKind.WindowChanged, row.kind)
        assertEquals(CodexResetSource.Observed, row.source)
        assertEquals(now + 30, row.at)
        assertEquals(80.0, row.before!!, 0.01)
        assertEquals(0.0, row.after!!, 0.01)
    }
    @Test fun expiredWindowRecordsOnlyOneObservedTransitionEvenAfterLongGap() {
        val ledger = CodexResetTracker.observe(baseline(), quota(now + 10 * 18000, used = 0.0), now + 9 * 18000)
        assertEquals(1, ledger.events.size)
        assertEquals(CodexResetKind.AutomaticPrimary, ledger.events.single().kind)
        assertEquals(now + 1000, ledger.events.single().at)
        assertEquals(ledger.events, CodexResetTracker.observe(ledger, quota(now + 10 * 18000), now + 9 * 18000 + 1).events)
    }
    @Test fun weeklyAndPrimaryAreSeparateObservations() {
        val first = quota(now + 100).copy(secondary = CodexQuotaWindow(90.0, 604800, null, now + 100))
        val old = CodexResetTracker.observe(CodexResetLedger(), first, now)
        val next = first.copy(primary = first.primary!!.copy(resetAtEpochSeconds = now + 18100),
            secondary = first.secondary!!.copy(resetAtEpochSeconds = now + 604900))
        val kinds = CodexResetTracker.observe(old, next, now + 101).events.map { it.kind }.toSet()
        assertEquals(setOf(CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly), kinds)
    }
    @Test fun noEventForUsageDropOrChangedDurationOrTinyClockShift() {
        val old = baseline()
        assertTrue(CodexResetTracker.observe(old, quota(used = 0.0), now + 1).events.isEmpty())
        assertTrue(CodexResetTracker.observe(old, quota(now + 2000, duration = 3600), now + 1).events.isEmpty())
        assertTrue(CodexResetTracker.observe(old, quota(now + 1050), now + 1).events.isEmpty())
    }
    @Test fun missingFieldsDoNotErasePriorObservation() {
        val old = baseline()
        val ledger = CodexResetTracker.observe(old, quota().copy(primary = null), now + 1)
        assertEquals(old.previous?.primary, ledger.previous?.primary)
        assertEquals(3, ledger.previous?.available)
    }
    @Test fun relativeResetTimeTracksWithoutCreatingClockDriftEvents() {
        val first = quota(null).copy(primary = CodexQuotaWindow(80.0, 18000, 1000, null))
        val old = CodexResetTracker.observe(CodexResetLedger(), first, now)
        val next = first.copy(primary = first.primary!!.copy(resetAfterSeconds = 970))
        assertTrue(CodexResetTracker.observe(old, next, now + 30).events.isEmpty())
        assertEquals(now + 1000, old.previous?.primary?.resetAtEpochSeconds)
    }
    @Test fun balanceDecreaseIsNotCountedAsResetConsumption() {
        val ledger = CodexResetTracker.observe(baseline(), quota(count = 2), now + 10)
        assertEquals(CodexResetKind.BalanceChanged, ledger.events.single().kind)
        assertEquals(CodexResetSource.Observed, ledger.events.single().source)
    }
    @Test fun explicitCreditTimestampsDeduplicateAndMissingRedemptionTimeIsUnknown() {
        val rows = listOf(CodexResetCredit("available", "available", now - 100, now + 100, null),
            CodexResetCredit("expired", "expired", null, now - 50, null),
            CodexResetCredit("used", "used", null, null, null, now - 20),
            CodexResetCredit("unknown", "redeemed", null, null, null))
        val old = CodexResetTracker.observe(CodexResetLedger(), quota(credits = rows), now)
        assertEquals(setOf(CodexResetKind.CreditGranted, CodexResetKind.CreditExpired, CodexResetKind.ManualUse), old.events.map { it.kind }.toSet())
        assertTrue(old.events.all { it.source == CodexResetSource.Official })
        assertEquals(old.events, CodexResetTracker.observe(old, quota(credits = rows), now + 1).events)
    }
    @Test fun manualRecordsCanClassifyUnknownEventsWithoutDoublingCount() {
        val unknown = CodexResetTracker.observe(baseline(), quota(now + 18000), now + 1).events.single()
        val ledger = CodexResetLedger(events = listOf(unknown))
        val next = CodexResetTracker.register(ledger, unknown.copy(kind = CodexResetKind.ManualUse, source = CodexResetSource.Registered), now + 1)
        assertEquals(1, next.events.size)
        assertEquals(CodexResetSource.Registered, next.events.single().source)
    }
    @Test fun manualInputRejectsImpossibleOrMisattributedRecords() {
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(count = 0), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(count = 1000), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(at = now + 121), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(at = 0), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(source = CodexResetSource.Official), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(kind = CodexResetKind.BalanceChanged), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event().copy(note = "x".repeat(257)), now) }
        assertRejected { CodexResetTracker.register(CodexResetLedger(), event(id = ""), now) }
    }
    @Test fun historyIsBoundedAndWarnsWhenTruncated() {
        val rows = (0..500).map { event(id = "row-$it", at = now - it) }
        val ledger = CodexResetTracker.register(CodexResetLedger(events = rows), event(), now)
        assertEquals(500, ledger.events.size)
        assertTrue(ledger.truncated)
        assertTrue(CodexResetTracker.observe(ledger, quota(), now + 1).truncated)
    }
    @Test fun periodBoundariesAreLocalAndEndExclusive() {
        val range = CodexResetPeriods.range(CodexResetPeriod.Week, Instant.ofEpochSecond(now), zone, null)!!
        assertEquals(Instant.parse("2026-10-04T16:00:00Z").epochSecond, range.start)
        assertEquals(Instant.parse("2026-10-11T16:00:00Z").epochSecond, range.end)
        val ledger = CodexResetLedger(events = listOf(event("before", range.start - 1), event("start", range.start), event("end", range.end)))
        assertEquals(listOf("start"), CodexResetPeriods.events(ledger, range).map { it.id })
        val month = CodexResetPeriods.range(CodexResetPeriod.Month, Instant.ofEpochSecond(now), zone, null)!!
        assertEquals(Instant.parse("2026-09-30T16:00:00Z").epochSecond, month.start)
    }
    @Test fun daylightSavingWeekDoesNotAssumeSevenTimesTwentyFourHours() {
        val range = CodexResetPeriods.range(CodexResetPeriod.Week, Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("America/New_York"), null)!!
        assertEquals(7 * 86400L - 3600L, range.end - range.start)
    }
    @Test fun subscriptionMonthRequiresAnchorAndClampsShortMonths() {
        assertNull(CodexResetPeriods.range(CodexResetPeriod.Subscription, Instant.ofEpochSecond(now), zone, null))
        val date = Instant.parse("2024-03-01T00:00:00Z")
        val range = CodexResetPeriods.range(CodexResetPeriod.Subscription, date, ZoneId.of("UTC"), 31)!!
        assertEquals(Instant.parse("2024-02-29T00:00:00Z").epochSecond, range.start)
        assertEquals(Instant.parse("2024-03-31T00:00:00Z").epochSecond, range.end)
    }
    @Test fun identitiesAreStableOnlyForSameUserAndWorkspace() {
        fun jwt(sub: String, signature: String) = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"$sub\"}".toByteArray())}.$signature"
        val key = CodexResetIdentity.key(jwt("user-a", "a"), "workspace")
        // "user-ab" makes the unpadded payload length not divisible by 4.
        assertEquals(
            CodexResetIdentity.key(jwt("user-ab", "a"), "workspace"),
            CodexResetIdentity.key(jwt("user-ab", "b"), "workspace"),
        )
        assertEquals(key, CodexResetIdentity.key(jwt("user-a", "b"), "workspace"))
        assertNotEquals(key, CodexResetIdentity.key(jwt("user-b", "a"), "workspace"))
        assertNotEquals(key, CodexResetIdentity.key(jwt("user-a", "a"), "another"))
        assertNotEquals(CodexResetIdentity.key(jwt("user-a", "a"), ""), CodexResetIdentity.key(jwt("user-a", "b"), ""))
        assertNotEquals(CodexResetIdentity.key("opaque-a", "workspace"), CodexResetIdentity.key("opaque-b", "workspace"))
        assertEquals(64, key.length)
        assertFalse(key.contains("user-a"))
    }
    @Test fun tokenWorkspaceClaimMatchesExplicitWorkspaceWithoutGuessingIdentity() {
        val payload = """{"sub":"user-a","https://api.openai.com/auth":{"chatgpt_account_id":"workspace"}}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val first = "header.$encoded.a"
        val second = "header.$encoded.b"
        assertEquals(CodexResetIdentity.key(first, ""), CodexResetIdentity.key(second, "workspace"))
        assertNotEquals(CodexResetIdentity.key(first, ""), CodexResetIdentity.key(first, "another"))
    }
    @Test fun encryptedPayloadCodecRoundTripsAllLocalFields() {
        val old = baseline()
        val ledger = old.copy(subscriptionDay = 31, truncated = true, events = listOf(event().copy(note = "fixture", before = 80.0, after = 0.0)))
        assertEquals(ledger, CodexResetCodec.decode(CodexResetCodec.encode(ledger)))
        assertEquals(CodexResetLedger(), CodexResetCodec.decode(CodexResetCodec.encode(CodexResetLedger())))
        assertThrows(Exception::class.java) { CodexResetCodec.decode("not json") }
    }
}
