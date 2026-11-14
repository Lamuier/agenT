package com.lamuier.agenT.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode

data class WidgetThemeColors(
    val surface: Int,
    val surfaceContainer: Int,
    val surfaceVariant: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val tertiary: Int,
    val progressTrack: Int,
    val outline: Int,
) {
    companion object {
        fun resolvePair(
            context: Context,
            settings: ThemeSettings = ThemePreferences.get(context).read(),
        ): WidgetThemePair {
            val isSystem = settings.themeMode == ThemeMode.System
            val currentDark = when (settings.themeMode) {
                ThemeMode.System -> {
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                }
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            val lightTheme: WidgetThemeColors
            val darkTheme: WidgetThemeColors

            if (settings.colorPalette == ColorPalette.System &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                lightTheme = systemDynamic(context, dark = false).readableOnLightSurface()
                darkTheme = systemDynamic(context, dark = true).readableOnLightSurface()
            } else {
                lightTheme = staticPalette(settings.colorPalette, dark = false).readableOnLightSurface()
                darkTheme = staticPalette(settings.colorPalette, dark = true).readableOnLightSurface()
            }

            return when (settings.themeMode) {
                ThemeMode.System -> WidgetThemePair(
                    light = lightTheme,
                    dark = darkTheme,
                    current = if (currentDark) darkTheme else lightTheme,
                    isSystem = true,
                    isNight = currentDark,
                )
                ThemeMode.Light -> WidgetThemePair(
                    light = lightTheme,
                    dark = lightTheme,
                    current = lightTheme,
                    isSystem = false,
                    isNight = false,
                )
                ThemeMode.Dark -> WidgetThemePair(
                    light = darkTheme,
                    dark = darkTheme,
                    current = darkTheme,
                    isSystem = false,
                    isNight = true,
                )
            }
        }

        fun resolve(context: Context, settings: ThemeSettings = ThemePreferences.get(context).read()): WidgetThemeColors =
            resolvePair(context, settings).current

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        private fun systemDynamic(context: Context, dark: Boolean): WidgetThemeColors {
            val res = context.resources
            return if (dark) {
                WidgetThemeColors(
                    surface = systemColor(res, android.R.color.system_neutral1_900, "#13171E"),
                    surfaceContainer = systemColor(res, android.R.color.system_neutral1_800, "#1A1F28"),
                    surfaceVariant = systemColor(res, android.R.color.system_neutral2_800, "#242B36"),
                    onSurface = systemColor(res, android.R.color.system_neutral1_100, "#E8ECF2"),
                    onSurfaceVariant = systemColor(res, android.R.color.system_neutral2_200, "#9AA5B5"),
                    primary = systemColor(res, android.R.color.system_accent1_200, "#60A5FA"),
                    primaryContainer = systemColor(res, android.R.color.system_accent1_700, "#1E3A5F"),
                    onPrimaryContainer = systemColor(res, android.R.color.system_accent1_100, "#DCEBFF"),
                    secondary = systemColor(res, android.R.color.system_accent2_200, "#94A3B8"),
                    tertiary = systemColor(res, android.R.color.system_accent3_200, "#22D3EE"),
                    progressTrack = systemColor(res, android.R.color.system_neutral2_700, "#41464F"),
                    outline = systemColor(res, android.R.color.system_neutral2_600, "#3A4454"),
                )
            } else {
                WidgetThemeColors(
                    surface = systemColor(res, android.R.color.system_neutral1_10, "#FFFFFF"),
                    surfaceContainer = systemColor(res, android.R.color.system_neutral1_50, "#EEF1F5"),
                    surfaceVariant = systemColor(res, android.R.color.system_neutral2_100, "#E4E9F0"),
                    onSurface = systemColor(res, android.R.color.system_neutral1_900, "#11151A"),
                    onSurfaceVariant = systemColor(res, android.R.color.system_neutral2_700, "#5C6775"),
                    primary = systemColor(res, android.R.color.system_accent1_600, "#2563EB"),
                    primaryContainer = systemColor(res, android.R.color.system_accent1_100, "#DCEBFF"),
                    onPrimaryContainer = systemColor(res, android.R.color.system_accent1_900, "#0A2A5C"),
                    secondary = systemColor(res, android.R.color.system_accent2_600, "#475569"),
                    tertiary = systemColor(res, android.R.color.system_accent3_600, "#0E7490"),
                    progressTrack = systemColor(res, android.R.color.system_neutral2_200, "#D9DFEA"),
                    outline = systemColor(res, android.R.color.system_neutral2_400, "#C5CDD8"),
                )
            }
        }

        private fun systemColor(
            res: android.content.res.Resources,
            id: Int,
            fallback: String,
        ): Int = runCatching { res.getColor(id, null) }.getOrElse { fallback.toColorInt() }

        private fun staticPalette(palette: ColorPalette, dark: Boolean): WidgetThemeColors {
            val key = when (palette) {
                ColorPalette.Aurora -> if (dark) AuroraDark else AuroraLight
                ColorPalette.Ember -> if (dark) EmberDark else EmberLight
                ColorPalette.Violet -> if (dark) VioletDark else VioletLight
                ColorPalette.Pulse, ColorPalette.System -> if (dark) PulseDark else PulseLight
            }
            return key
        }

        private val PulseLight = WidgetThemeColors(
            surface = "#FFFFFF".toColorInt(),
            surfaceContainer = "#EEF1F5".toColorInt(),
            surfaceVariant = "#E4E9F0".toColorInt(),
            onSurface = "#11151A".toColorInt(),
            onSurfaceVariant = "#5C6775".toColorInt(),
            primary = "#2563EB".toColorInt(),
            primaryContainer = "#DCEBFF".toColorInt(),
            onPrimaryContainer = "#0A2A5C".toColorInt(),
            secondary = "#475569".toColorInt(),
            tertiary = "#0E7490".toColorInt(),
            progressTrack = "#D9DFEA".toColorInt(),
            outline = "#C5CDD8".toColorInt(),
        )

        private val PulseDark = WidgetThemeColors(
            surface = "#13171E".toColorInt(),
            surfaceContainer = "#1A1F28".toColorInt(),
            surfaceVariant = "#242B36".toColorInt(),
            onSurface = "#E8ECF2".toColorInt(),
            onSurfaceVariant = "#9AA5B5".toColorInt(),
            primary = "#60A5FA".toColorInt(),
            primaryContainer = "#1E3A5F".toColorInt(),
            onPrimaryContainer = "#DCEBFF".toColorInt(),
            secondary = "#94A3B8".toColorInt(),
            tertiary = "#22D3EE".toColorInt(),
            progressTrack = "#41464F".toColorInt(),
            outline = "#3A4454".toColorInt(),
        )

        private val AuroraLight = WidgetThemeColors(
            surface = "#FFFFFF".toColorInt(),
            surfaceContainer = "#EEF1F5".toColorInt(),
            surfaceVariant = "#E4E9F0".toColorInt(),
            onSurface = "#11151A".toColorInt(),
            onSurfaceVariant = "#5C6775".toColorInt(),
            primary = "#0F766E".toColorInt(),
            primaryContainer = "#CCFBF1".toColorInt(),
            onPrimaryContainer = "#134E4A".toColorInt(),
            secondary = "#54716C".toColorInt(),
            tertiary = "#0369A1".toColorInt(),
            progressTrack = "#D9DFEA".toColorInt(),
            outline = "#C5CDD8".toColorInt(),
        )

        private val AuroraDark = WidgetThemeColors(
            surface = "#13171E".toColorInt(),
            surfaceContainer = "#1A1F28".toColorInt(),
            surfaceVariant = "#242B36".toColorInt(),
            onSurface = "#E8ECF2".toColorInt(),
            onSurfaceVariant = "#9AA5B5".toColorInt(),
            primary = "#2DD4BF".toColorInt(),
            primaryContainer = "#115E59".toColorInt(),
            onPrimaryContainer = "#CCFBF1".toColorInt(),
            secondary = "#A7C1BB".toColorInt(),
            tertiary = "#38BDF8".toColorInt(),
            progressTrack = "#41464F".toColorInt(),
            outline = "#3A4454".toColorInt(),
        )

        private val EmberLight = WidgetThemeColors(
            surface = "#FFFFFF".toColorInt(),
            surfaceContainer = "#EEF1F5".toColorInt(),
            surfaceVariant = "#E4E9F0".toColorInt(),
            onSurface = "#11151A".toColorInt(),
            onSurfaceVariant = "#5C6775".toColorInt(),
            primary = "#B45309".toColorInt(),
            primaryContainer = "#FEF3C7".toColorInt(),
            onPrimaryContainer = "#78350F".toColorInt(),
            secondary = "#6B5E4E".toColorInt(),
            tertiary = "#C2410C".toColorInt(),
            progressTrack = "#D9DFEA".toColorInt(),
            outline = "#C5CDD8".toColorInt(),
        )

        private val EmberDark = WidgetThemeColors(
            surface = "#13171E".toColorInt(),
            surfaceContainer = "#1A1F28".toColorInt(),
            surfaceVariant = "#242B36".toColorInt(),
            onSurface = "#E8ECF2".toColorInt(),
            onSurfaceVariant = "#9AA5B5".toColorInt(),
            primary = "#FBBF24".toColorInt(),
            primaryContainer = "#92400E".toColorInt(),
            onPrimaryContainer = "#FEF3C7".toColorInt(),
            secondary = "#D4C4B0".toColorInt(),
            tertiary = "#FB923C".toColorInt(),
            progressTrack = "#41464F".toColorInt(),
            outline = "#3A4454".toColorInt(),
        )

        private val VioletLight = WidgetThemeColors(
            surface = "#FFFFFF".toColorInt(),
            surfaceContainer = "#EEF1F5".toColorInt(),
            surfaceVariant = "#E4E9F0".toColorInt(),
            onSurface = "#11151A".toColorInt(),
            onSurfaceVariant = "#5C6775".toColorInt(),
            primary = "#4F46E5".toColorInt(),
            primaryContainer = "#E0E7FF".toColorInt(),
            onPrimaryContainer = "#312E81".toColorInt(),
            secondary = "#5B5F7A".toColorInt(),
            tertiary = "#7C3AED".toColorInt(),
            progressTrack = "#D9DFEA".toColorInt(),
            outline = "#C5CDD8".toColorInt(),
        )

        private val VioletDark = WidgetThemeColors(
            surface = "#13171E".toColorInt(),
            surfaceContainer = "#1A1F28".toColorInt(),
            surfaceVariant = "#242B36".toColorInt(),
            onSurface = "#E8ECF2".toColorInt(),
            onSurfaceVariant = "#9AA5B5".toColorInt(),
            primary = "#A5B4FC".toColorInt(),
            primaryContainer = "#3730A3".toColorInt(),
            onPrimaryContainer = "#E0E7FF".toColorInt(),
            secondary = "#C5C8DC".toColorInt(),
            tertiary = "#C4B5FD".toColorInt(),
            progressTrack = "#41464F".toColorInt(),
            outline = "#3A4454".toColorInt(),
        )
    }
}

/**
 * 浅色壁纸上，系统色板的次要文字和描边可能淡到融进白底。
 * 只在浅色表面把不够对比的墨色和边线往黑压，深色表面保持原样。
 */
internal fun WidgetThemeColors.readableOnLightSurface(): WidgetThemeColors {
    if (relativeLuminance(surface) < 0.5) return this
    return copy(
        onSurface = darkenUntil(onSurface, surface, 7.0),
        onSurfaceVariant = darkenUntil(onSurfaceVariant, surface, 5.5),
        outline = darkenUntil(outline, surface, 3.0),
    )
}

private fun darkenUntil(color: Int, background: Int, minimum: Double): Int {
    if (contrastRatio(color, background) >= minimum) return color
    var low = 0f
    var high = 1f
    var best = Color.BLACK
    repeat(14) {
        val amount = (low + high) / 2f
        val mixed = mixTowardBlack(color, amount)
        if (contrastRatio(mixed, background) >= minimum) {
            best = mixed
            high = amount
        } else {
            low = amount
        }
    }
    return best
}

private fun mixTowardBlack(color: Int, amount: Float): Int {
    fun channel(value: Int) = (value * (1f - amount)).toInt().coerceIn(0, 255)
    return Color.rgb(channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
}

private fun contrastRatio(foreground: Int, background: Int): Double {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
        val s = value / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(Color.red(color)) +
        0.7152 * channel(Color.green(color)) +
        0.0722 * channel(Color.blue(color))
}

/** Alpha-blend [color] onto opaque black/white for tinted chip backgrounds. */
fun WidgetThemeColors.tintedSurface(fraction: Float = 0.18f): Int {
    val r = Color.red(primary)
    val g = Color.green(primary)
    val b = Color.blue(primary)
    val br = Color.red(surface)
    val bg = Color.green(surface)
    val bb = Color.blue(surface)
    return Color.rgb(
        (br + (r - br) * fraction).toInt().coerceIn(0, 255),
        (bg + (g - bg) * fraction).toInt().coerceIn(0, 255),
        (bb + (b - bb) * fraction).toInt().coerceIn(0, 255),
    )
}

data class WidgetThemePair(
    val light: WidgetThemeColors,
    val dark: WidgetThemeColors,
    val current: WidgetThemeColors,
    val isSystem: Boolean,
    val isNight: Boolean,
) {
    fun applyTextColor(views: RemoteViews, viewId: Int, lightColor: Int, darkColor: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(viewId, "setTextColor", lightColor, darkColor)
        } else {
            views.setTextColor(viewId, if (isNight) darkColor else lightColor)
        }
    }

    fun applyOnSurface(views: RemoteViews, vararg viewIds: Int) {
        viewIds.forEach { viewId ->
            applyTextColor(views, viewId, light.onSurface, dark.onSurface)
        }
    }

    fun applyOnSurfaceVariant(views: RemoteViews, vararg viewIds: Int) {
        viewIds.forEach { viewId ->
            applyTextColor(views, viewId, light.onSurfaceVariant, dark.onSurfaceVariant)
        }
    }

    fun applySurfaceAndOutline(views: RemoteViews, hasOutline: Boolean = true) {
        val lightBlend = WidgetAppearance.blend(light.surface, light.primary, 0.025f)
        val darkBlend = WidgetAppearance.blend(dark.surface, dark.primary, 0.025f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(R.id.widget_surface, "setColorFilter", lightBlend, darkBlend)
            if (hasOutline) {
                views.setColorInt(R.id.widget_outline, "setColorFilter", light.outline, dark.outline)
            }
        } else {
            views.setInt(R.id.widget_surface, "setColorFilter", WidgetAppearance.blend(current.surface, current.primary, 0.025f))
            if (hasOutline) {
                views.setInt(R.id.widget_outline, "setColorFilter", current.outline)
            }
        }
    }

    fun applyDivider(views: RemoteViews, viewId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(viewId, "setBackgroundColor", light.surfaceVariant, dark.surfaceVariant)
        } else {
            views.setInt(viewId, "setBackgroundColor", current.surfaceVariant)
        }
    }

    fun applyBoardDivider(views: RemoteViews, viewId: Int) {
        val lightDiv = WidgetAppearance.blend(light.surface, light.onSurface, 0.12f)
        val darkDiv = WidgetAppearance.blend(dark.surface, dark.onSurface, 0.12f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(viewId, "setBackgroundColor", lightDiv, darkDiv)
        } else {
            views.setInt(viewId, "setBackgroundColor", WidgetAppearance.blend(current.surface, current.onSurface, 0.12f))
        }
    }

    fun applyRefreshButton(context: Context, views: RemoteViews) {
        views.setInt(R.id.widget_refresh, "setBackgroundResource", R.drawable.widget_refresh_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.widget_refresh,
                "setBackgroundTintList",
                ColorStateList.valueOf(light.surfaceVariant),
                ColorStateList.valueOf(dark.surfaceVariant),
            )
            views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, Color.WHITE))
            views.setColorInt(R.id.widget_refresh, "setColorFilter", light.onSurfaceVariant, dark.onSurfaceVariant)
        } else {
            views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, current.onSurfaceVariant))
        }
    }

    fun applyChipBackground(views: RemoteViews, viewId: Int, lightColor: Int = light.surfaceContainer, darkColor: Int = dark.surfaceContainer) {
        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_metric_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                viewId,
                "setBackgroundTintList",
                ColorStateList.valueOf(lightColor),
                ColorStateList.valueOf(darkColor),
            )
        }
    }

    fun applySections(views: RemoteViews, compact: Boolean = false) {
        if (compact) return
        val lightCursor = WidgetAppearance.blend(light.surface, light.primary, 0.09f)
        val darkCursor = WidgetAppearance.blend(dark.surface, dark.primary, 0.09f)
        val lightCodex = WidgetAppearance.blend(light.surface, light.tertiary, 0.09f)
        val darkCodex = WidgetAppearance.blend(dark.surface, dark.tertiary, 0.09f)
        views.setInt(R.id.widget_cursor_section, "setBackgroundResource", R.drawable.widget_panel_background)
        views.setInt(R.id.widget_codex_section, "setBackgroundResource", R.drawable.widget_panel_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.widget_cursor_section,
                "setBackgroundTintList",
                ColorStateList.valueOf(lightCursor),
                ColorStateList.valueOf(darkCursor),
            )
            views.setColorStateList(
                R.id.widget_codex_section,
                "setBackgroundTintList",
                ColorStateList.valueOf(lightCodex),
                ColorStateList.valueOf(darkCodex),
            )
        }
    }

    fun applyTileTint(views: RemoteViews, viewId: Int, serviceOk: Boolean, serviceColor: Int) {
        val lightTint = if (serviceOk) WidgetAppearance.blend(light.surface, light.onSurface, 0.05f)
            else WidgetAppearance.blend(light.surface, serviceColor, 0.16f)
        val darkTint = if (serviceOk) WidgetAppearance.blend(dark.surface, dark.onSurface, 0.05f)
            else WidgetAppearance.blend(dark.surface, serviceColor, 0.16f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(viewId, "setColorFilter", lightTint, darkTint)
        } else {
            val currentTint = if (serviceOk) WidgetAppearance.blend(current.surface, current.onSurface, 0.05f)
                else WidgetAppearance.blend(current.surface, serviceColor, 0.16f)
            views.setInt(viewId, "setColorFilter", currentTint)
        }
    }

    fun applyStatusPanel(views: RemoteViews, panelSurfaceId: Int, lightTint: Int, darkTint: Int) {
        val lightBlend = WidgetAppearance.blend(light.surface, lightTint, 0.055f)
        val darkBlend = WidgetAppearance.blend(dark.surface, darkTint, 0.055f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(panelSurfaceId, "setColorFilter", lightBlend, darkBlend)
        } else {
            views.setInt(panelSurfaceId, "setColorFilter", WidgetAppearance.blend(current.surface, if (isNight) darkTint else lightTint, 0.055f))
        }
    }

    fun accent(percent: Double?, lightNormal: Int = light.primary, darkNormal: Int = dark.primary): Pair<Int, Int> =
        WidgetAppearance.accent(percent, lightNormal, light.onSurfaceVariant, dark = false) to
            WidgetAppearance.accent(percent, darkNormal, dark.onSurfaceVariant, dark = true)

    fun applyAccentText(views: RemoteViews, viewId: Int, percent: Double?, lightNormal: Int = light.primary, darkNormal: Int = dark.primary) {
        val (l, d) = accent(percent, lightNormal, darkNormal)
        applyTextColor(views, viewId, l, d)
    }
}
