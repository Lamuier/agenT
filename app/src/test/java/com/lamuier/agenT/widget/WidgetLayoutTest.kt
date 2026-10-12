package com.lamuier.agenT.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class WidgetLayoutTest {
    private fun layout(name: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(
        listOf(File("src/main/res/layout"), File("app/src/main/res/layout")).first { it.isDirectory }, "$name.xml"))

    @Test fun detailWidgetStacksCursorAboveCodexAndKeepsAllThreeMeters() {
        for (name in listOf("widget_assistants_detail", "widget_assistants_detail_compact", "widget_assistants_detail_large")) {
            val document = layout(name)
            val elements = document.getElementsByTagName("*")
            val byId = (0 until elements.length).map { elements.item(it) as Element }
                .associateBy { it.getAttributeNS(ANDROID, "id") }
            val sections = byId.getValue("@+id/widget_usage_sections")
            assertEquals("vertical", sections.getAttributeNS(ANDROID, "orientation"))
            val childIds = (0 until sections.childNodes.length).mapNotNull {
                (sections.childNodes.item(it) as? Element)?.getAttributeNS(ANDROID, "id")
            }
            assertEquals(listOf("@+id/widget_cursor_section", "@+id/widget_usage_divider", "@+id/widget_codex_section"), childIds)
            for (id in listOf("widget_cursor_progress", "widget_codex_primary_progress", "widget_codex_weekly_progress",
                "widget_codex_primary_reset", "widget_codex_weekly_reset", "widget_updated", "widget_refresh",
                "widget_cursor_plan", "widget_codex_plan", "widget_outline")) {
                assertNotNull("Details must retain $id", byId["@+id/$id"])
            }
        }
    }

    @Test fun miniWidgetKeepsHorizontalProviderColumns() {
        val outer = layout("widget_assistants_mini").getElementsByTagName("LinearLayout").item(0) as Element
        assertEquals("horizontal", outer.getAttributeNS(ANDROID, "orientation"))
    }

    private companion object { const val ANDROID = "http://schemas.android.com/apk/res/android" }
}
