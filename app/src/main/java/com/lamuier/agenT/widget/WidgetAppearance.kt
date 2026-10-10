package com.lamuier.agenT.widget

internal enum class WidgetQuotaTone { Unknown, Normal, NearLimit, Exhausted }

/** Quota semantics are independent of provider and device palette. */
internal object WidgetAppearance {
    fun tone(percent: Double?): WidgetQuotaTone = when {
        percent == null || !percent.isFinite() -> WidgetQuotaTone.Unknown
        percent >= 100.0 -> WidgetQuotaTone.Exhausted
        percent >= 90.0 -> WidgetQuotaTone.NearLimit
        else -> WidgetQuotaTone.Normal
    }

    fun blend(base: Int, tint: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int =
            (((base ushr shift) and 255) * (1 - amount) + ((tint ushr shift) and 255) * amount).toInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun accent(percent: Double?, normal: Int, muted: Int, dark: Boolean): Int = when (tone(percent)) {
        WidgetQuotaTone.Unknown -> muted
        WidgetQuotaTone.Normal -> normal
        WidgetQuotaTone.NearLimit -> if (dark) 0xFFE2B66B.toInt() else 0xFFBA842E.toInt()
        WidgetQuotaTone.Exhausted -> if (dark) 0xFFE89D91.toInt() else 0xFFB95F55.toInt()
    }
}
