package com.lamuier.agenT.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.network.StatusJsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetSourcesTest {
    @Test fun emptyAndNewSourceOnlyStatesNeverLeaveCursorOrCodexPlaceholders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = android.widget.FrameLayout(context)
        val kind = TallCursorWidgetProvider::class.java
        val empty = AgenTWidgetUpdater.renderSourcesPreview(context, emptyList(), kind, 334, 252).apply(context, parent)
        assertEquals(android.view.View.VISIBLE, empty.findViewById<android.view.View>(com.lamuier.agenT.R.id.widget_multi_empty).visibility)
        assertEquals(android.view.View.GONE, empty.findViewById<android.view.View>(com.lamuier.agenT.R.id.widget_next_page).visibility)
        val sources = listOf(WidgetSource("deepseek", "DeepSeek", listOf(WidgetMetric("CNY", "-0.25"), WidgetMetric("USD", "12.50"))),
            WidgetSource("kimi_code", "Kimi Code", listOf(WidgetMetric("5h", "—")), attention = "Update connection"))
        val views = AgenTWidgetUpdater.renderSourcesPreview(context, sources, kind, 334, 252).apply(context, parent)
        val text = mutableListOf<String>()
        fun walk(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view is android.widget.TextView) text += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(views)
        assertTrue(text.containsAll(listOf("DeepSeek", "Kimi Code", "CNY -0.25")))
        assertFalse(text.any { it.contains("Cursor") || it.contains("Codex") })
        assertTrue(text.any { it.contains("Update connection") })
        val mini = AgenTWidgetUpdater.renderSourcesPreview(context, sources, MiniCursorWidgetProvider::class.java, 174, 68).apply(context, parent)
        assertEquals("CNY -0.25", mini.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_metric_value).text.toString())
        assertEquals("DeepSeek +1", mini.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_source_name).text.toString())
    }
    @Test fun pageSurvivesDeletionAndCapacityChangesWithoutSkippingTheLastPage() {
        val ids = WidgetSourcePolicy.order
        assertEquals(listOf("deepseek", "kimi_code"), WidgetSourcePolicy.page(ids, "kimi_code", 2))
        assertEquals("cursor", WidgetSourcePolicy.next(ids, "kimi_code", 2))
        assertEquals(listOf("kimi_code"), WidgetSourcePolicy.page(ids, "kimi_code", 3))
        assertEquals("cursor", WidgetSourcePolicy.next(ids, "kimi_code", 3))
        assertEquals(listOf("cursor"), WidgetSourcePolicy.page(ids, "deleted", 1))
        assertEquals("cursor", WidgetSourcePolicy.next(ids.take(3), "deepseek", 2))
        assertEquals(emptyList<String>(), WidgetSourcePolicy.page(emptyList(), "cursor", 1))
        assertNull(WidgetSourcePolicy.next(emptyList(), null, 1))
    }
    @Test fun placementFillsOddCountsAndKeepsTheFourCellGrid() {
        val usage = WidgetSourcePolicy.placement(3, 4, 334, tiny = false, status = false)
        assertEquals(3, usage.columns)
        assertFalse(usage.spanLast || usage.stackCompact)
        val status = WidgetSourcePolicy.placement(3, 4, 334, tiny = false, status = true)
        assertEquals(1, status.columns)
        assertTrue(status.stackCompact && !status.spanLast)
        val grid = WidgetSourcePolicy.placement(4, 4, 334, tiny = false, status = false)
        assertEquals(2, grid.columns)
        assertFalse(grid.spanLast || grid.stackCompact)
        assertEquals(1, WidgetSourcePolicy.placement(1, 2, 334, tiny = false, status = false).columns)
        val pair = WidgetSourcePolicy.placement(2, 2, 334, tiny = false, status = true)
        assertEquals(2, pair.columns)
        assertFalse(pair.spanLast)
        assertEquals(1, WidgetSourcePolicy.placement(2, 2, 334, tiny = false, status = false).columns)
    }

    @Test fun balanceWithoutSplitAndPlainTextMetricsFallBackToTextLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sources = listOf(
            WidgetSource("cursor", "Cursor", listOf(WidgetMetric("自有", "41.9%", 41.9, caption = "12天")), fetchedAt = "2026-10-19 10:15:00"),
            WidgetSource("codex", "Codex", listOf(WidgetMetric("5小时", "10%", 10.0, caption = "06:00")), fetchedAt = "2026-10-19 10:15:00"),
            WidgetSource("deepseek", "DeepSeek", listOf(WidgetMetric("CNY", "10.79"), WidgetMetric("USD", "2")), "余额可用", "2026-10-19 10:15:00"),
        )
        val root = laidOut(context, sources, TallCursorWidgetProvider::class.java, 334, 252, "usage-3-balance-text")
        val text = mutableListOf<String>()
        var gauges = 0
        fun scan(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view.id == com.lamuier.agenT.R.id.widget_gauge) gauges++
            if (view is android.widget.TextView) text += view.text.toString()
            if (view is android.view.ViewGroup) repeat(view.childCount) { scan(view.getChildAt(it)) }
        }
        scan(root)
        assertEquals(2, gauges)
        assertTrue(text.contains("余额可用"))
        assertFalse(text.any { it.contains("●") })
        assertNoClipping(root)
    }

    @Test fun shortResetShowsClockForTodayAndDateOtherwise() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-10-19T06:00:00Z")
        fun at(text: String) = java.time.Instant.parse(text).epochSecond
        assertEquals("15:57", WidgetSourcePolicy.shortReset(at("2026-10-19T07:57:00Z"), zone, now))
        assertEquals("10/25", WidgetSourcePolicy.shortReset(at("2026-10-24T21:59:00Z"), zone, now))
        assertEquals("10/20", WidgetSourcePolicy.shortReset(at("2026-10-19T17:00:00Z"), zone, now))
    }

    @Test fun threeAndFourSourceLayoutsUseTheAvailableWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usage = listOf(
            WidgetSource("cursor", "Cursor", listOf(WidgetMetric("自有", "41.9%", 41.9, "周期剩 12天", "12天"), WidgetMetric("三方", "52.7%", 52.7)), "本周期 42.85%", "2026-10-19 10:15:00"),
            WidgetSource("codex", "Codex", listOf(WidgetMetric("5小时", "100%", 100.0, "03/20 06:00", "06:00"), WidgetMetric("每周", "79%", 79.0, "03/25 08:00", "03/25")), fetchedAt = "2026-10-19 10:15:00"),
            WidgetSource("deepseek", "DeepSeek", listOf(WidgetMetric("CNY", "10.79"), WidgetMetric("赠送", "1.25"), WidgetMetric("充值", "9.54")), "余额可用", "2026-10-19 10:15:00",
                split = listOf(1.25, 9.54)),
        )
        val usageRoot = laidOut(context, usage, TallCursorWidgetProvider::class.java, 334, 252, "usage-3")
        val gauges = mutableListOf<android.view.View>()
        val gaugeText = mutableListOf<String>()
        fun scan(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view.id == com.lamuier.agenT.R.id.widget_gauge) gauges += view
            if (view is android.widget.TextView) gaugeText += view.text.toString()
            if (view is android.view.ViewGroup) repeat(view.childCount) { scan(view.getChildAt(it)) }
        }
        scan(usageRoot)
        assertEquals(5, gauges.size)
        assertTrue(gaugeText.containsAll(listOf("41.9%", "52.7%", "100%", "79%", "自有 · 12天", "每周 · 03/25")))
        assertTrue(gaugeText.any { it.contains("CNY") && it.contains("10.79") })
        assertTrue(gaugeText.any { it.contains("● 赠送 1.25") && it.contains("● 充值 9.54") })
        assertFalse(gaugeText.any { it.contains("本周期") || it.contains("余额可用") })
        assertTrue(gauges.all { it.width > 0 && it.height > 0 })
        val usageRows = usageRoot.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows)
        assertEquals(1, usageRows.childCount)
        val usageRow = usageRows.getChildAt(0) as android.view.ViewGroup
        assertEquals(3, usageRow.childCount)
        repeat(3) { index ->
            val card = usageRow.getChildAt(index)
            assertTrue(card.width > usageRoot.width / 4)
            assertTrue(card.width < usageRoot.width / 2)
            assertTrue(card.height > usageRoot.height / 2)
        }
        assertNoClipping(usageRoot)

        val status = listOf(
            service("cursor", "Cursor", listOf("Automations", "Review Agents", "CLI"), "CLI degraded"),
            service("codex", "Codex", listOf("ChatGPT Desktop", "Web"), "Degraded performance of the API"),
            service("deepseek", "DeepSeek", listOf("DeepSeek V4 Pro API服务(API Service)", "DeepSeek V4.1 Flash API服务(API Service)"), "DeepSeek V4.1 Flash 服务波动"),
        )
        val statusRoot = laidOut(context, status, TallCursorStatusWidgetProvider::class.java, 334, 252, "status-3")
        val statusRows = statusRoot.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows)
        assertEquals(3, statusRows.childCount)
        repeat(3) { index ->
            val card = (statusRows.getChildAt(index) as android.view.ViewGroup).getChildAt(0)
            assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, card.layoutParams.width)
            assertTrue(card.width > statusRoot.width * 3 / 4)
        }
        val visible = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view is android.widget.TextView) visible += view.text.toString()
            if (view is android.view.ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        collect(statusRoot)
        assertTrue(visible.any { it.contains("Degraded performance of the API") && !it.endsWith("…") })
        assertTrue(visible.any { it.contains("DeepSeek V4.1 Flash 服务波动") && !it.endsWith("…") })
        assertNoClipping(statusRoot)

        val four = usage + WidgetSource("kimi_code", "Kimi Code", listOf(WidgetMetric("5小时", "18%", 18.0)))
        val fourRoot = laidOut(context, four, TallCursorWidgetProvider::class.java, 334, 252, "usage-4")
        val fourRows = fourRoot.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows)
        assertEquals(2, fourRows.childCount)
        assertEquals(2, (fourRows.getChildAt(0) as android.view.ViewGroup).childCount)
        assertEquals(2, (fourRows.getChildAt(1) as android.view.ViewGroup).childCount)
        assertNoClipping(fourRoot)

        val single = laidOut(context, usage.take(1), TallCursorWidgetProvider::class.java, 334, 252, "usage-1")
        val singleCard = (single.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows).getChildAt(0) as android.view.ViewGroup).getChildAt(0)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, singleCard.layoutParams.width)
        assertTrue(singleCard.width > single.width * 3 / 4)
        assertNoClipping(single)

        val paired = laidOut(context, usage.take(2), TallCursorWidgetProvider::class.java, 334, 252, "usage-2")
        assertEquals(2, paired.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows).childCount)
        assertNoClipping(paired)
        val statusPair = laidOut(context, status.take(2), TallCursorStatusWidgetProvider::class.java, 334, 252, "status-2")
        val statusPairRow = statusPair.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows).getChildAt(0) as android.view.ViewGroup
        assertEquals(2, statusPairRow.childCount)
        assertNoClipping(statusPair)
        val pairText = mutableListOf<String>()
        fun collectPair(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view is android.widget.TextView) pairText += view.text.toString()
            if (view is android.view.ViewGroup) repeat(view.childCount) { collectPair(view.getChildAt(it)) }
        }
        collectPair(statusPair)
        assertTrue(pairText.any { it.contains("Degraded performance of the API") && !it.endsWith("…") })
        val kimiFooter = usage.take(2) + WidgetSource("kimi_code", "Kimi Code", listOf(
            WidgetMetric("5小时", "18%", 18.0), WidgetMetric("每周", "36%", 36.0)), fetchedAt = "2026-10-19 10:15:00")
        val kimiRoot = laidOut(context, kimiFooter, TallCursorWidgetProvider::class.java, 334, 252, "usage-3-meters")
        val kimiRow = kimiRoot.findViewById<android.view.ViewGroup>(com.lamuier.agenT.R.id.widget_source_rows).getChildAt(0) as android.view.ViewGroup
        assertEquals(3, kimiRow.childCount)
        assertTrue(kimiRow.getChildAt(2).width > kimiRoot.width / 4)
        assertNoClipping(kimiRoot)
    }

    private fun service(id: String, title: String, components: List<String>, incident: String) = WidgetSource(id, title, status = com.lamuier.agenT.model.ServiceStatus(
        "", com.lamuier.agenT.model.StatusIndicator.None, null, "", components.mapIndexed { index, name ->
            com.lamuier.agenT.model.StatusComponent("$id-$index", name, com.lamuier.agenT.model.ComponentStatus.Operational, index)
        }, emptyList(), emptyList(), listOf(com.lamuier.agenT.model.StatusIncident(
            id, incident, "resolved", "minor", "2026-10-15T05:04:00Z", "2026-10-15T05:04:00Z", "2026-10-15T05:04:00Z",
            null, null, null, listOf(incident), emptyList(),
        )), com.lamuier.agenT.network.StatusJsonParser.nowStamp(), false,
    ), fetchedAt = com.lamuier.agenT.network.StatusJsonParser.nowStamp(), cached = false)

    private fun laidOut(context: Context, sources: List<WidgetSource>, provider: Class<*>, widthDp: Int, heightDp: Int, name: String): android.view.View {
        val parent = android.widget.FrameLayout(context)
        val root = AgenTWidgetUpdater.renderSourcesPreview(context, sources, provider, widthDp, heightDp).apply(context, parent)
        parent.addView(root, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, width, height)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        parent.draw(android.graphics.Canvas(bitmap))
        val output = java.io.File("build/reports/widget-review/adapt-$name.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return parent
    }

    private fun assertNoClipping(root: android.view.View) {
        val failures = mutableListOf<String>()
        fun walk(view: android.view.View, top: Int, bottom: Int) {
            if (view.visibility != android.view.View.VISIBLE) return
            val childTop = top + view.top
            val clipBottom = minOf(bottom, childTop + view.height)
            if (view is android.widget.TextView && view.text.isNotBlank()) {
                val text = view.layout
                if (text == null || view.height <= 0 || text.height > view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 1 ||
                    childTop + view.compoundPaddingTop + text.height > clipBottom + 1 ||
                    (0 until text.lineCount).any { text.getLineWidth(it) > view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1 }) {
                    failures += "${view.text}: ${view.width}x${view.height}"
                }
            }
            if (view is android.view.ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it), childTop, clipBottom) }
        }
        walk(root, 0, root.height)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun narrowAndLargeFontCapacityPreservesReadableSourceCards() {
        assertEquals(1, WidgetSourcePolicy.capacity("Mini", 334, 68, 1f, 4))
        assertEquals(4, WidgetSourcePolicy.capacity("Tall", 334, 252, 1f, 4))
        assertEquals(1, WidgetSourcePolicy.capacity("Tall", 250, 180, 1f, 4))
        assertEquals(1, WidgetSourcePolicy.capacity("Tall", 334, 252, 2f, 4))
    }
    @Test fun pagesAreIndependentAndDeletionClearsOnlyItsOwnRecord() {
        val pages = WidgetPages(ApplicationProvider.getApplicationContext<Context>())
        pages.select(101, "deepseek"); pages.select(102, "kimi_code")
        assertEquals("deepseek", WidgetPages(ApplicationProvider.getApplicationContext()).anchor(101))
        pages.clear(intArrayOf(101))
        assertNull(pages.anchor(101)); assertEquals("kimi_code", pages.anchor(102))
    }
    @Test fun hiddenPagesAndMissingOrStaleStatusCannotProduceAnAllHealthySummary() {
        fun source(id: String, indicator: StatusIndicator, at: String = StatusJsonParser.nowStamp()) = WidgetSource(id, id,
            status = ServiceStatus("", indicator, null, "", emptyList(), emptyList(), emptyList(), emptyList(), at, false))
        val healthy = source("deepseek", StatusIndicator.None)
        assertEquals(StatusIndicator.None, WidgetSourcePolicy.indicator(listOf(healthy)))
        assertEquals(StatusIndicator.Unknown, WidgetSourcePolicy.indicator(listOf(healthy, WidgetSource("kimi_code", "Kimi Code"))))
        assertEquals(StatusIndicator.Unknown, WidgetSourcePolicy.indicator(listOf(source("deepseek", StatusIndicator.None, "2000-01-01 00:00:00"))))
        assertEquals(StatusIndicator.Critical, WidgetSourcePolicy.indicator(listOf(healthy, source("kimi_code", StatusIndicator.Critical))))
        assertEquals(StatusIndicator.Critical, WidgetSourcePolicy.indicator(listOf(source("kimi_code", StatusIndicator.Critical).let {
            it.copy(status = it.status!!.copy(partialComponents = true))
        })))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = android.widget.FrameLayout(context)
        val stale = source("deepseek", StatusIndicator.None, "2000-01-01 00:00:00")
        val mini = AgenTWidgetUpdater.renderSourcesPreview(context, listOf(stale), MiniCursorStatusWidgetProvider::class.java, 174, 68).apply(context, parent)
        assertEquals(com.lamuier.agenT.util.StatusPresentation.compactIndicatorLabel(StatusIndicator.Unknown, context.resources),
            mini.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_metric_value).text.toString())
        val outage = AgenTWidgetUpdater.renderSourcesPreview(context, listOf(source("deepseek", StatusIndicator.Critical)),
            MiniCursorStatusWidgetProvider::class.java, 174, 68).apply(context, parent)
        assertEquals(android.view.View.GONE, outage.findViewById<android.view.View>(com.lamuier.agenT.R.id.widget_next_page).visibility)
        assertNull(WidgetSourcePolicy.indicator(emptyList()))
    }
    @Test fun cardNavigationIsOneShotAndRejectsUnknownSources() {
        WidgetNavigation.open("kimi_code", DashboardTab.Usage)
        val first = requireNotNull(WidgetNavigation.destination.value)
        WidgetNavigation.open("deepseek", DashboardTab.Status)
        WidgetNavigation.consume(first)
        assertEquals("deepseek", WidgetNavigation.destination.value!!.source)
        WidgetNavigation.consume(WidgetNavigation.destination.value!!)
        assertNull(WidgetNavigation.destination.value)
        WidgetNavigation.open("not-a-source", DashboardTab.Usage)
        assertNull(WidgetNavigation.destination.value)
    }
}
