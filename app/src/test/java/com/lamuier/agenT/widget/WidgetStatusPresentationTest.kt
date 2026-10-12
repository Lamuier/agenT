package com.lamuier.agenT.widget

import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusComponent
import com.lamuier.agenT.model.StatusIndicator
import org.junit.Assert.*
import org.junit.Test

class WidgetStatusPresentationTest {
    @Test fun allHealthyComponentsFitWithoutInterleavingProviders() {
        assertEquals(8, WidgetStatusPresentation.components(status(), 8).size)
        assertEquals(listOf("item-0", "item-1", "item-2"), WidgetStatusPresentation.components(status(), 3).map { it.name })
    }
    @Test fun compactLayoutNeverHidesAnOutageBehindHealthyRows() {
        val source = status().copy(components = status().components.mapIndexed { index, item ->
            if (index == 7) item.copy(status = ComponentStatus.MajorOutage) else item
        })
        assertEquals("item-7", WidgetStatusPresentation.components(source, 3).first().name)
        assertTrue(WidgetStatusPresentation.components(null, 8).isEmpty())
    }
    @Test fun providerColumnRemovesOnlyRedundantNamePrefixes() {
        assertEquals("ChatGPT Desktop", WidgetStatusPresentation.componentName("Codex", "Codex in ChatGPT Desktop"))
        assertEquals("Web", WidgetStatusPresentation.componentName("Codex", "Codex Web"))
        assertEquals("Automations", WidgetStatusPresentation.componentName("Cursor", "Automations"))
    }
    private fun status() = ServiceStatus("", StatusIndicator.None, null, "", (0..7).map {
        StatusComponent("$it", "item-$it", ComponentStatus.Operational, it)
    }, emptyList(), emptyList(), emptyList(), "", false)
}
