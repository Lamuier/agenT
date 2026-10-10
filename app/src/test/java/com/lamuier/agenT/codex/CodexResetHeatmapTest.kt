package com.lamuier.agenT.codex

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CodexResetHeatmapTest {
    private fun public(id: String, time: String, type: String = "regular"): CodexResetTimelineEntry {
        val at = Instant.parse(time).epochSecond
        return CodexResetTimelineEntry(at, public = CodexPublicReset(id, type, at, "", "observed", null, null))
    }
    @Test fun calendarUsesSundayColumnsWithOutsideRangePadding() {
        val map = CodexResetHeatmap.build(emptyList(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-08"), ZoneOffset.UTC)
        assertEquals(DayOfWeek.SUNDAY, map.weeks.first().first().date.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, map.weeks.last().last().date.dayOfWeek)
        assertEquals(8, map.days.size)
        assertFalse(map.weeks.first().first().inRange)
        assertFalse(map.weeks.last().last().inRange)
        assertNull(CodexResetHeatmap.dayAt(map, 0, 0))
        assertNull(CodexResetHeatmap.dayAt(map, 999, 1))
    }
    @Test fun dateBucketsFollowDisplayTimeZoneAcrossMidnight() {
        val entry = public("edge", "2026-10-01T23:30:00Z")
        val from = LocalDate.parse("2026-10-01"); val to = from.plusDays(2)
        fun date(zone: ZoneId) = CodexResetHeatmap.build(listOf(entry), from, to, zone).days.single { it.entries.isNotEmpty() }.date
        assertEquals(from, date(ZoneOffset.UTC))
        assertEquals(from.plusDays(1), date(ZoneId.of("Asia/Shanghai")))
    }
    @Test fun daylightSavingDoesNotCreateDuplicateCalendarDays() {
        val entries = listOf(public("before", "2026-03-08T09:55:00Z"), public("after", "2026-03-08T10:05:00Z"))
        val date = LocalDate.parse("2026-03-08")
        val map = CodexResetHeatmap.build(entries, date, date, ZoneId.of("America/Los_Angeles"))
        assertEquals(1, map.days.size)
        assertEquals(2, map.days.single().entries.size)
    }
    @Test fun mixedTypesArePreservedAndPublicDuplicatesDoNotDoubleCount() {
        val regular = public("same-id", "2026-10-08T12:00:00Z")
        val banked = public("banked", "2026-10-08T10:00:00Z", "banked")
        val local = CodexResetTimelineEntry(banked.at, local = CodexResetEvent("same-id", CodexResetKind.BalanceChanged,
            CodexResetSource.Observed, banked.at))
        val day = LocalDate.parse("2026-10-08")
        val cells = CodexResetHeatmap.build(listOf(regular, regular, banked, local), day, day, ZoneOffset.UTC).days.single()
        assertEquals(3, cells.entries.size)
        assertEquals(setOf(ResetHeatmapKind.Regular, ResetHeatmapKind.Banked, ResetHeatmapKind.Other), cells.kinds)
        assertEquals(regular.at, cells.entries.first().at)
    }
    @Test fun absentRecordsAreNotInventedAndEntriesOutsideTheWindowAreIgnored() {
        val from = LocalDate.parse("2026-10-01")
        val map = CodexResetHeatmap.build(listOf(public("old", "2026-09-30T12:00:00Z")), from, from.plusDays(7), ZoneOffset.UTC)
        assertTrue(map.days.all { it.entries.isEmpty() && it.kinds.isEmpty() })
    }
    @Test fun leapDayAppearsOnceAndInvalidOrExcessiveWindowsAreRejected() {
        val map = CodexResetHeatmap.build(emptyList(), LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01"), ZoneOffset.UTC)
        assertEquals(3, map.days.size)
        assertEquals(1, map.days.count { it.date == LocalDate.parse("2024-02-29") })
        assertThrows(IllegalArgumentException::class.java) {
            CodexResetHeatmap.build(emptyList(), map.to, map.from, ZoneOffset.UTC)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodexResetHeatmap.build(emptyList(), map.from, map.from.plusDays(400), ZoneOffset.UTC)
        }
    }
}
