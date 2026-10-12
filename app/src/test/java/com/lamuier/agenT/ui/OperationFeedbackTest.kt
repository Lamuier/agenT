package com.lamuier.agenT.ui

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.geometry.Rect
import com.lamuier.agenT.widget.WidgetVisuals

class OperationFeedbackTest {
    @Test fun multiProviderRefreshConfirmsOnlyAfterEveryPartSucceeds() {
        var confirmations = 0
        val batch = OperationBatch(setOf("cursor", "codex", "status")) { confirmations++ }
        batch.finish("status", true); batch.finish("cursor", true)
        assertEquals(0, confirmations)
        batch.finish("codex", true); batch.finish("codex", true)
        assertEquals(1, confirmations)
    }
    @Test fun aFailedOrCanceledPartNeverConfirmsPartialSuccess() {
        for (failed in listOf("cursor", "codex", "status")) {
            var confirmations = 0
            val batch = OperationBatch(setOf("cursor", "codex", "status")) { confirmations++ }
            listOf("status", "cursor", "codex").forEach { batch.finish(it, it != failed) }
            assertEquals(0, confirmations)
        }
    }
    @Test fun unrelatedResultsAndDuplicateCallbacksCannotCompleteABatch() {
        var confirmations = 0
        val batch = OperationBatch(setOf("usage", "tasks")) { confirmations++ }
        batch.finish("automatic", true); batch.finish("usage", true); batch.finish("usage", true)
        assertEquals(0, confirmations)
        batch.finish("tasks", true)
        assertEquals(1, confirmations)
    }
    @Test fun emptyRefreshDoesNotConfirm() {
        var confirmations = 0
        OperationBatch(emptySet()) { confirmations++ }.finish("automatic", true)
        assertEquals(0, confirmations)
    }
    @Test fun foldKeepsContentInAnUnobstructedPane() {
        val window = Rect(0f, 0f, 1000f, 800f)
        assertEquals(Rect(0f, 0f, 600f, 800f), safePane(window, listOf(Rect(600f, 0f, 620f, 800f))))
        assertEquals(Rect(0f, 0f, 1000f, 800f), safePane(window, emptyList()))
        assertEquals(Rect(0f, 320f, 1000f, 800f), safePane(window, listOf(Rect(0f, 300f, 1000f, 320f))))
    }
    @Test fun oversizedWidgetSurfacesFitThePixelBudget() {
        for ((width, height) in listOf(4000 to 3000, 320 to 150, 150 to 320)) {
            val bounded = WidgetVisuals.boundedSurfaceSize(width, height, 40000)
            assertTrue(bounded.first.toLong() * bounded.second <= 40000)
            assertTrue(bounded.first <= width && bounded.second <= height)
        }
    }
}
