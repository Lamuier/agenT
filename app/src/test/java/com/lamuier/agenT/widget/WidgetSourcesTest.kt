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
        assertEquals("antigravity", WidgetSourcePolicy.next(ids, "kimi_code", 2))
        assertEquals(listOf("antigravity"), WidgetSourcePolicy.page(ids, "antigravity", 2))
        assertEquals("cursor", WidgetSourcePolicy.next(ids, "antigravity", 2))
        assertEquals(listOf("cursor"), WidgetSourcePolicy.page(ids, "deleted", 1))
        assertEquals("cursor", WidgetSourcePolicy.next(ids.take(3), "deepseek", 2))
        assertEquals(emptyList<String>(), WidgetSourcePolicy.page(emptyList(), "cursor", 1))
        assertNull(WidgetSourcePolicy.next(emptyList(), null, 1))
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
