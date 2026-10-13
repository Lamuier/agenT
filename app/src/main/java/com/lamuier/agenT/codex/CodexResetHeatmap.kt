package com.lamuier.agenT.codex

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal enum class ResetHeatmapKind { Regular, Banked }
internal data class ResetHeatmapDay(val date: LocalDate, val inRange: Boolean,
    val entries: List<CodexResetTimelineEntry>, val kinds: Set<ResetHeatmapKind>)
internal data class ResetHeatmapData(val from: LocalDate, val to: LocalDate, val weeks: List<List<ResetHeatmapDay>>) {
    val days get() = weeks.flatten().filter { it.inRange }
}

/** Calendar aggregation only: a blank day means no supplied records, never proof of no reset. */
internal object CodexResetHeatmap {
    fun build(entries: List<CodexResetTimelineEntry>, from: LocalDate, to: LocalDate, zone: ZoneId): ResetHeatmapData {
        require(!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 370)
        val grouped = entries.filter { it.public.type in listOf("regular", "banked") }.distinctBy { it.public.id }
            .groupBy { Instant.ofEpochSecond(it.at).atZone(zone).toLocalDate() }
        val first = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val last = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val weeks = mutableListOf<List<ResetHeatmapDay>>()
        var start = first
        while (!start.isAfter(last)) {
            weeks += (0L..6L).map { offset ->
                val date = start.plusDays(offset)
                val valid = !date.isBefore(from) && !date.isAfter(to)
                val dayEntries = if (valid) grouped[date].orEmpty().sortedByDescending { it.at } else emptyList()
                ResetHeatmapDay(date, valid, dayEntries, dayEntries.map {
                    if (it.public.type == "banked") ResetHeatmapKind.Banked else ResetHeatmapKind.Regular
                }.toSet())
            }
            start = start.plusWeeks(1)
        }
        return ResetHeatmapData(from, to, weeks)
    }
    fun dayAt(data: ResetHeatmapData, column: Int, row: Int): ResetHeatmapDay? =
        data.weeks.getOrNull(column)?.getOrNull(row)?.takeIf { it.inRange }
}
