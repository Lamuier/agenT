package com.lamuier.agenT.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared app descriptions must not regress to branding for only one provider. */
class UnifiedBrandCopyTest {
    @Test
    fun ongoingSettingsAndSystemChannelsDescribeLiveActivities() {
        val resourceRoot = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        for ((locale, expected) in mapOf("values" to "实时活动", "values-en" to "Live activities")) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val entries = factory.newDocumentBuilder()
                .parse(File(resourceRoot, "$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until entries.length).associate { index ->
                val node = entries.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertEquals("$locale channel", expected, strings["notification_live_channel_name"])
            assertTrue("$locale ongoing switch", strings.getValue("notification_live_updates_label").startsWith(expected))
            assertTrue("$locale description", strings.getValue("notification_live_updates_description").contains("Codex"))
            assertTrue("$locale removed Billing page", "tab_billing" !in strings)
        }
    }

    @Test
    fun sharedDescriptionsIncludeBothAssistantsInEverySupportedLanguage() {
        val resourceRoot = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val sharedDescriptions = listOf(
            "notification_live_channel_description",
            "notification_reminder_channel_description",
        )
        for (locale in listOf("values", "values-en")) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val entries = factory.newDocumentBuilder()
                .parse(File(resourceRoot, "$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until entries.length).associate { index ->
                val node = entries.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertEquals("$locale app name", "agenT", strings["app_name"])
            for (key in listOf("widget_status_brand", "widget_refresh_description")) {
                val copy = strings.getValue(key)
                assertTrue("$locale/$key must describe the configured sources", copy.contains(if (locale == "values") "来源" else "sources") || copy == "服务状态" || copy == "Service status")
            }
            assertFalse(strings.getValue("widget_tall_description").contains("Antigravity"))
            assertTrue(strings.getValue("widget_tall_description").contains(if (locale == "values") "来源" else "sources"))
            for (key in sharedDescriptions) {
                val copy = strings.getValue(key)
                assertTrue("$locale/$key must describe both providers: $copy",
                    copy.contains("Cursor") && copy.contains("Codex"))
            }
        }
    }
}
