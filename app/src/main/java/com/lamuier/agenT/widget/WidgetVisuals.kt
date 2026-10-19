package com.lamuier.agenT.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object WidgetVisuals {
    /** Match Stats-style cards: pronounced, soft rounded corners. */
    private const val CORNER_RADIUS_DP = 24f
    private const val DONUT_STROKE_DP = 12f
    private const val CHIP_RADIUS_DP = 999f
    private const val SPLIT_GAP_DEGREES = 5f
    private const val GLASS_ALPHA_DARK = 0xE3
    private const val GLASS_ALPHA_LIGHT = 0xF2

    internal fun boundedSurfaceSize(width: Int, height: Int, pixelBudget: Long): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val scale = kotlin.math.sqrt((pixelBudget.coerceAtLeast(1).toDouble() / (w.toLong() * h)).coerceAtMost(1.0))
        var boundedWidth = (w * scale).toInt().coerceAtLeast(1)
        var boundedHeight = (h * scale).toInt().coerceAtLeast(1)
        if (boundedWidth.toLong() * boundedHeight > pixelBudget.coerceAtLeast(1)) {
            if (boundedWidth >= boundedHeight) boundedWidth = (pixelBudget.coerceAtLeast(1) / boundedHeight).toInt().coerceAtLeast(1)
            else boundedHeight = (pixelBudget.coerceAtLeast(1) / boundedWidth).toInt().coerceAtLeast(1)
        }
        return boundedWidth to boundedHeight
    }

    fun glassSurface(color: Int): Int {
        val luminance =
            (0.299f * android.graphics.Color.red(color) +
                0.587f * android.graphics.Color.green(color) +
                0.114f * android.graphics.Color.blue(color)) / 255f
        val alpha = if (luminance < 0.5f) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }

    fun surfaceBitmap(
        context: Context,
        widgetId: Int,
        color: Int,
        fallbackWidthDp: Int,
        fallbackHeightDp: Int,
    ): Bitmap {
        val (widthPx, heightPx) = widgetSizePx(context, widgetId, fallbackWidthDp, fallbackHeightDp)
        val metrics = context.resources.displayMetrics
        val budget = (metrics.widthPixels.toLong() * metrics.heightPixels / 4).coerceIn(1, 262144)
        val (width, height) = boundedSurfaceSize(widthPx, heightPx, budget)
        val scale = width.toFloat() / widthPx.coerceAtLeast(1)
        return roundRectBitmap(width, height, color, dp(context, CORNER_RADIUS_DP) * scale)
    }

    fun donutBitmap(
        context: Context,
        sizeDp: Int,
        progress: Int,
        progressColor: Int,
        trackColor: Int,
        strokeDp: Float = DONUT_STROKE_DP,
    ): Bitmap {
        val size = dp(context, sizeDp.toFloat()).roundToInt().coerceIn(1, 256)
        val stroke = dp(context, strokeDp)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = stroke / 2f + dp(context, 1f)
        val oval = RectF(inset, inset, size - inset, size - inset)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = trackColor
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = progressColor
        }
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)
        val sweep = (progress.coerceIn(0, 100) / 100f) * 360f
        if (sweep > 0.5f) {
            canvas.drawArc(oval, -90f, sweep, false, progressPaint)
        }
        return bitmap
    }

    /** One ring split into proportional segments, e.g. granted versus topped-up balance. */
    fun splitDonutBitmap(
        context: Context,
        sizeDp: Int,
        fractions: List<Float>,
        colors: List<Int>,
        trackColor: Int,
        strokeDp: Float = DONUT_STROKE_DP,
    ): Bitmap {
        val size = dp(context, sizeDp.toFloat()).roundToInt().coerceIn(1, 256)
        val stroke = dp(context, strokeDp)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = stroke / 2f + dp(context, 1f)
        val oval = RectF(inset, inset, size - inset, size - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        paint.color = trackColor
        canvas.drawArc(oval, -90f, 360f, false, paint)
        val parts = fractions.map { if (it.isFinite()) it.coerceAtLeast(0f) else 0f }
        val total = parts.sum()
        val visible = parts.count { it > 0f }
        if (total <= 0f) return bitmap
        val gap = if (visible > 1) SPLIT_GAP_DEGREES else 0f
        paint.strokeCap = if (visible > 1) Paint.Cap.BUTT else Paint.Cap.ROUND
        val room = 360f - gap * visible
        var start = -90f + gap / 2f
        parts.forEachIndexed { index, part ->
            if (part <= 0f) return@forEachIndexed
            val sweep = part / total * room
            paint.color = colors.getOrElse(index) { trackColor }
            canvas.drawArc(oval, start, sweep, false, paint)
            start += sweep + gap
        }
        return bitmap
    }

    /** Bake track and fill into one image so each RemoteViews meter has independent geometry. */
    fun quotaBarBitmap(percent: Double?, fillColor: Int, trackColor: Int): Bitmap {
        val width = 512
        val height = 8
        val fraction = percent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)?.div(100.0) ?: 0.0
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = height / 2f
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        if (fraction > 0.0) {
            paint.color = fillColor
            canvas.drawRoundRect(0f, 0f, (width * fraction).toFloat(), height.toFloat(), radius, radius, paint)
        }
        return bitmap
    }

    /** Status-page style lamp: tinted disc plus a glanceable glyph, not a progress ring. */
    fun statusBadgeBitmap(
        context: Context,
        sizeDp: Int,
        color: Int,
        glyph: StatusBadgeGlyph,
    ): Bitmap {
        val size = dp(context, sizeDp.toFloat()).roundToInt().coerceIn(1, 256)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = android.graphics.Color.argb(
                46,
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color),
            )
        }
        canvas.drawCircle(cx, cy, size / 2f, background)
        val stroke = dp(context, if (sizeDp <= 32) 2.2f else 3.6f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        when (glyph) {
            StatusBadgeGlyph.Ok -> drawCheck(canvas, size, paint)
            StatusBadgeGlyph.Warning -> drawExclamation(canvas, size, paint)
            StatusBadgeGlyph.Error -> drawCross(canvas, size, paint)
            StatusBadgeGlyph.Maintenance -> drawWrench(canvas, size, paint)
            StatusBadgeGlyph.Unknown -> drawDash(canvas, size, paint)
        }
        return bitmap
    }

    fun chipBitmap(
        context: Context,
        color: Int,
        widthDp: Int,
        heightDp: Int,
    ): Bitmap {
        val width = dp(context, widthDp.toFloat()).roundToInt().coerceIn(1, 256)
        val height = dp(context, heightDp.toFloat()).roundToInt().coerceIn(1, 256)
        return roundRectBitmap(width, height, color, dp(context, CHIP_RADIUS_DP))
    }

    fun refreshIconBitmap(
        context: Context,
        color: Int,
        sizeDp: Int = 18,
    ): Bitmap {
        val size = dp(context, sizeDp.toFloat()).roundToInt().coerceIn(1, 256)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1.8f)
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        val inset = dp(context, 3f)
        val oval = RectF(inset, inset, size - inset, size - inset)
        canvas.drawArc(oval, 40f, 260f, false, paint)
        val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        val cx = size / 2f + dp(context, 4.5f)
        val cy = inset + dp(context, 1.5f)
        canvas.drawCircle(cx, cy, dp(context, 1.6f), tipPaint)
        return bitmap
    }

    private fun drawCheck(canvas: Canvas, size: Int, paint: Paint) {
        val path = Path().apply {
            moveTo(size * 0.28f, size * 0.52f)
            lineTo(size * 0.42f, size * 0.68f)
            lineTo(size * 0.74f, size * 0.34f)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawExclamation(canvas: Canvas, size: Int, paint: Paint) {
        canvas.drawLine(size * 0.5f, size * 0.28f, size * 0.5f, size * 0.58f, paint)
        val dot = Paint(paint).apply { style = Paint.Style.FILL }
        canvas.drawCircle(size * 0.5f, size * 0.72f, paint.strokeWidth * 0.72f, dot)
    }

    private fun drawCross(canvas: Canvas, size: Int, paint: Paint) {
        canvas.drawLine(size * 0.34f, size * 0.34f, size * 0.66f, size * 0.66f, paint)
        canvas.drawLine(size * 0.66f, size * 0.34f, size * 0.34f, size * 0.66f, paint)
    }

    private fun drawWrench(canvas: Canvas, size: Int, paint: Paint) {
        val cx = size / 2f
        val cy = size / 2f
        canvas.save()
        canvas.rotate(-40f, cx, cy)
        canvas.drawLine(cx, cy - size * 0.06f, cx, cy + size * 0.28f, paint)
        val head = RectF(
            cx - size * 0.16f,
            cy - size * 0.34f,
            cx + size * 0.16f,
            cy - size * 0.02f,
        )
        canvas.drawArc(head, 210f, 300f, false, paint)
        canvas.restore()
    }

    private fun drawDash(canvas: Canvas, size: Int, paint: Paint) {
        canvas.drawLine(size * 0.32f, size * 0.5f, size * 0.68f, size * 0.5f, paint)
    }

    private fun roundRectBitmap(
        widthPx: Int,
        heightPx: Int,
        color: Int,
        radiusPx: Float,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val radius = min(radiusPx, min(widthPx, heightPx) / 2f)
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
            radius,
            radius,
            paint,
        )
        return bitmap
    }

    private fun widgetSizePx(
        context: Context,
        widgetId: Int,
        fallbackWidthDp: Int,
        fallbackHeightDp: Int,
    ): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        // Prefer the mid-point of min/max so corner radius isn't crushed when
        // launchers report oversized MAX_* bounds and the bitmap is fitXY-scaled.
        val widthDp = averageSizeDp(minW, maxW, fallbackWidthDp)
        val heightDp = averageSizeDp(minH, maxH, fallbackHeightDp)
        return Pair(
            (widthDp * density).roundToInt().coerceAtLeast(1),
            (heightDp * density).roundToInt().coerceAtLeast(1),
        )
    }

    private fun averageSizeDp(minDp: Int, maxDp: Int, fallbackDp: Int): Int {
        val candidates = listOf(minDp, maxDp).filter { it > 0 }
        if (candidates.isEmpty()) return fallbackDp
        return max(candidates.average().roundToInt(), fallbackDp)
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        )
}
