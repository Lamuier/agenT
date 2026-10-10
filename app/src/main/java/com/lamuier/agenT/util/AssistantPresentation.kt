package com.lamuier.agenT.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal object AssistantPresentation {
    fun useTwoColumns(availableWidthDp: Float, fontScale: Float): Boolean =
        availableWidthDp >= 2 * (280f * fontScale.coerceAtLeast(1f) + 40f) + 16f

    fun percent(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        val rounded = (value.coerceAtLeast(0.0) * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}%"
        else String.format(Locale.US, "%.1f%%", rounded)
    }

    fun plan(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        if (value != value.lowercase(Locale.ROOT)) value
        else value.replace('_', ' ').split(' ').joinToString(" ") {
            it.replaceFirstChar { letter -> letter.titlecase(Locale.ROOT) }
        }
    }

    fun resetTime(epochSeconds: Long, zone: ZoneId, now: Instant = Instant.now()): String {
        val reset = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        val pattern = if (reset.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "MM/dd HH:mm"
        return reset.format(DateTimeFormatter.ofPattern(pattern))
    }

    fun updatedClock(value: String?, zone: ZoneId): String? =
        DisplayTime.parseStoredLocal(value)?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm"))
}
