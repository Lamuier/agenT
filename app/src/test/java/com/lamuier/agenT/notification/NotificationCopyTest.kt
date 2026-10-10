package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import com.lamuier.agenT.model.StatusIndicator
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class NotificationCopyTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val entries = factory.newDocumentBuilder().parse(File(root, "$locale/strings.xml")).getElementsByTagName("string")
        return (0 until entries.length).associate { index ->
            val item = entries.item(index)
            item.attributes.getNamedItem("name").nodeValue to item.textContent
        }
    }

    @Test fun formalIsDefaultAndUnmappedFactsAndChannelNamesAreNeverRestyled() {
        val formal = NotificationCopy()
        val cat = NotificationCopy(true)
        for ((id, _) in NotificationCopy.catResources) assertEquals(id, formal.resource(id))
        for (id in listOf(R.string.agent_service_cursor_title, R.string.notification_codex_primary,
            R.string.notification_codex_weekly, R.string.notification_live_channel_name)) {
            assertEquals(id, cat.resource(id))
        }
    }

    @Test fun everyStateAndShortChipHasLocalizedCatCopyWithSafeLengths() {
        val cat = NotificationCopy(true)
        for (locale in listOf("values", "values-en")) {
            val copy = strings(locale)
            for (state in IslandMonitorState.entries) {
                assertNotEquals(state.labelRes, cat.resource(state.labelRes))
                assertNotEquals(state.chipRes, cat.resource(state.chipRes))
                val label = copy.getValue(names.getValue(cat.resource(state.labelRes)))
                val chip = copy.getValue(names.getValue(cat.resource(state.chipRes)))
                assertTrue(label.isNotBlank())
                assertFalse(label.contains("%"))
                assertFalse(chip.contains("%"))
                assertTrue("$locale short chip: $chip", chip.length <= 7)
                if (locale == "values") assertTrue("Chinese island caption: $label", label.length <= 4)
            }
        }
    }

    @Test fun allCatTemplatesPreserveFactsAndPlaceholderSignaturesInBothLanguages() {
        val placeholders = Regex("%[0-9]+\\\$[sd]|%%")
        for (locale in listOf("values", "values-en")) {
            val copy = strings(locale)
            for ((formal, cat) in NotificationCopy.catResources) {
                val original = copy.getValue(names.getValue(formal))
                val variant = copy.getValue(names.getValue(cat))
                assertEquals("$locale ${names.getValue(formal)}", placeholders.findAll(original).map { it.value }.toList(),
                    placeholders.findAll(variant).map { it.value }.toList())
            }
        }
    }

    @Test fun serviceSeverityRemainsExplicitInTheCatDetails() {
        val cat = NotificationCopy(true)
        val copy = strings("values")
        assertEquals("有点慢喵", copy.getValue(names.getValue(cat.resource(NotificationCopy.serviceDetail(StatusIndicator.Minor)))))
        assertEquals("部分中断喵", copy.getValue(names.getValue(cat.resource(NotificationCopy.serviceDetail(StatusIndicator.Major)))))
        assertEquals("严重故障喵", copy.getValue(names.getValue(cat.resource(NotificationCopy.serviceDetail(StatusIndicator.Critical)))))
        assertEquals("维护中喵", copy.getValue(names.getValue(cat.resource(NotificationCopy.serviceDetail(StatusIndicator.Maintenance)))))
        assertEquals(R.string.status_indicator_unknown, cat.resource(NotificationCopy.serviceDetail(StatusIndicator.Unknown)))
    }

    @Test fun multipleAttentionCaptionsRemainShortAndCountIsNotAQuota() {
        for (locale in listOf("values", "values-en")) for (cat in listOf(false, true)) {
            val resources = strings(locale)
            val copy = NotificationCopy(cat)
            for (state in IslandMonitorState.entries) for (count in listOf(0, 1, 2, 6, 20)) {
                val caption = copy.shortCaption(state, count) { resources.getValue(names.getValue(it)) }
                assertTrue("$locale $caption", caption.length <= 7)
                assertFalse(caption.contains("%"))
                if (count in 1..9) assertTrue(caption.endsWith("+$count"))
                if (count > 9) assertTrue(caption.endsWith("+9+"))
            }
        }
    }

    @Test fun styleOnlyRefreshCannotFetchOrReplayThresholdOrResetNotices() {
        val main = root.parentFile
        val source = File(main, "java/com/lamuier/agenT/notification/AgenTNotificationCoordinator.kt").readText()
        val callback = source.substringAfter("fun refreshCopyFromCache()").substringBefore("private fun cachedCursor")
        assertTrue(callback.contains("postCombinedLiveUpdate"))
        assertTrue(callback.contains("settings.liveUpdatesEnabled"))
        for (forbidden in listOf("fetchUsage", "refreshLive", "scheduleReset", "postCodexThresholdReminder",
            "maybePostThresholdReminder", "resetReminder", "markThresholdReminded", "markResetNotified")) {
            assertFalse(forbidden, callback.contains(forbidden))
        }
    }
}
