package com.lamuier.agenT.notification

import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class IslandMonitorStateTest {
    @Test fun maintenanceCopyUsesClearServiceMaintenanceWording() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val items = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "res/values/strings.xml")).getElementsByTagName("string")
        val strings = (0 until items.length).associate { index ->
            val item = items.item(index)
            item.attributes.getNamedItem("name").nodeValue to item.textContent
        }
        assertEquals("服务维护", strings["island_monitor_maintenance"])
        assertEquals("服务维护", strings["island_monitor_maintenance_chip"])
        assertFalse(strings.values.any { it.contains("服务检修") })
    }

    @Test fun runningCopyUsesMonitoringByDefaultAndPatrolOnlyForCatStyle() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        for ((locale, expected) in mapOf("values" to "正在监控", "values-en" to "Monitor")) {
            val items = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until items.length).associate { index ->
                val item = items.item(index)
                item.attributes.getNamedItem("name").nodeValue to item.textContent
            }
            assertEquals(expected, strings["island_monitor_running"])
            assertEquals(if (locale == "values") "领地巡视" else "Patrol", strings["island_monitor_running_cat"])
            assertEquals(if (locale == "values") "正在监控" else "Watch", strings["notification_multi_running_chip"])
            assertEquals(if (locale == "values") "领地巡视" else "Prowl", strings["notification_multi_running_chip_cat"])
            assertFalse(strings.getValue("notification_monitor_running").contains("巡视"))
            assertFalse(strings.getValue("notification_monitor_running").contains("Patrol"))
            assertTrue(strings.getValue("notification_monitor_running").contains("%1\$s"))
            assertFalse(strings.values.any { it.contains("助手巡航") || it.contains("On duty") })
        }
    }

    @Test fun serviceIssueCopyIsClearInBothLanguages() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        for ((locale, expected) in mapOf("values" to ("服务异常" to "服务异常"),
            "values-en" to ("Service issue" to "Issue"))) {
            val items = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until items.length).associate { index ->
                val item = items.item(index)
                item.attributes.getNamedItem("name").nodeValue to item.textContent
            }
            assertEquals(expected.first, strings["island_monitor_service"])
            assertEquals(expected.second, strings["island_monitor_service_chip"])
            assertFalse(strings.values.any { it.contains("服务卡壳") || it.contains("Service hiccup") })
        }
    }

    private fun status(indicator: StatusIndicator, age: Int = 0, partial: Boolean = false) = ServiceStatus(
        "", indicator, null, "", emptyList(), emptyList(), emptyList(), emptyList(), "", false,
        cacheAgeSeconds = age, partialComponents = partial)

    @Test fun healthyMonitoringIsRunningAndThresholdsUseUsedQuota() {
        assertEquals(IslandMonitorState.Running, IslandMonitorState.select(listOf(79.9), listOf(status(StatusIndicator.None)), true))
        assertEquals(IslandMonitorState.LowQuota, IslandMonitorState.select(listOf(10.0, 80.0), emptyList(), true))
        assertEquals(IslandMonitorState.EmptyQuota, IslandMonitorState.select(listOf(100.0), emptyList(), true))
        assertEquals(IslandMonitorState.Running, IslandMonitorState.select(listOf(Double.NaN, 10.0), emptyList(), true))
    }
    @Test fun severeServiceFailureWinsOverQuotaWhileExhaustionWinsOverMinorDegradation() {
        assertEquals(IslandMonitorState.ServiceIssue, IslandMonitorState.select(listOf(100.0), listOf(status(StatusIndicator.Critical)), true))
        assertEquals(IslandMonitorState.ServiceIssue, IslandMonitorState.select(listOf(80.0), listOf(status(StatusIndicator.Major)), true))
        assertEquals(IslandMonitorState.EmptyQuota, IslandMonitorState.select(listOf(100.0), listOf(status(StatusIndicator.Minor)), true))
        assertEquals(IslandMonitorState.ServiceIssue, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.None), status(StatusIndicator.Minor)), true))
        assertEquals(IslandMonitorState.Maintenance, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.Maintenance)), true))
    }
    @Test fun staleOrUnknownFactsAreNotPresentedAsFreshFailuresOrHealthyServices() {
        assertEquals(IslandMonitorState.Syncing, IslandMonitorState.select(emptyList(), listOf(status(StatusIndicator.None)), false))
        assertEquals(IslandMonitorState.Syncing, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.Critical, 1201)), true))
        assertEquals(IslandMonitorState.Syncing, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.Maintenance, 1201)), true))
        assertEquals(IslandMonitorState.Syncing, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.Unknown)), true))
        assertEquals(IslandMonitorState.Syncing, IslandMonitorState.select(listOf(10.0), listOf(status(StatusIndicator.None, partial = true)), true))
        assertEquals(IslandMonitorState.ServiceIssue, IslandMonitorState.select(emptyList(), listOf(status(StatusIndicator.Major)), false))
    }
    @Test fun chineseCapsuleCopyFitsFourCharactersAndLocalizedStatusChipsFitSeven() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        listOf("values", "values-en").forEach { locale ->
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "res/$locale/strings.xml")).getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) }.filter {
                it.attributes.getNamedItem("name").nodeValue.startsWith("island_monitor_")
            }.forEach { item ->
                val name = item.attributes.getNamedItem("name").nodeValue
                if (locale == "values") assertTrue(item.textContent.length <= 4)
                if (name.endsWith("_chip") || name == "island_monitor_running") assertTrue(item.textContent.length <= 7)
                assertFalse(item.textContent.contains("%"))
            }
        }
    }
}
