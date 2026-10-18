package com.lamuier.agenT.ui

import com.lamuier.agenT.codex.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ResetDashboardMathTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private fun event(id: String, type: String, at: String) = CodexPublicReset(id, type, Instant.parse(at).epochSecond, "", "observed", null, null)
    @Test fun suppliedRecordsAreDeduplicatedAndTypesNeverBecomeCreditQuantities() {
        val regular = event("a", "regular", "2026-10-12T01:00:00Z")
        val grant = event("b", "banked", "2026-10-13T01:00:00Z")
        val range = CodexResetPeriods.range(CodexResetPeriod.Week, now, ZoneOffset.UTC, null)!!
        val data = resetDashboardData(listOf(regular, regular, grant, grant, event("unknown", "window", "2026-10-13T00:00:00Z")), range, now, ZoneOffset.UTC)
        assertEquals(1, data.regular); assertEquals(1, data.banked); assertEquals(2, data.total)
        assertEquals(2, data.recordedDays); assertEquals(3, data.days.size)
        assertEquals(data.total, data.days.sumOf { it.regular + it.banked })
    }
    @Test fun displayZoneBoundariesAndEmptyPeriodsStayExact() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = CodexResetPeriods.range(CodexResetPeriod.Month, now, zone, null)!!
        val data = resetDashboardData(listOf(event("a", "regular", "2026-10-12T23:30:00Z"), event("old", "regular", "2026-09-30T01:00:00Z")), range, now, zone)
        assertEquals(1, data.total)
        assertEquals(LocalDate.parse("2026-10-13"), data.days.single { it.regular == 1 }.date)
        val empty = resetDashboardData(emptyList(), range, now, zone)
        assertEquals(0, empty.total); assertNull(empty.latest); assertEquals(0, empty.recordedDays)
        assertTrue(empty.days.all { it.regular == 0 && it.banked == 0 })
    }
}
