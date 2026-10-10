package com.lamuier.agenT.widget

import com.lamuier.agenT.model.BillingCycle
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.Credits
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.PlanInfo
import com.lamuier.agenT.model.StatusComponent
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.model.Subscription
import com.lamuier.agenT.model.TotalFormat
import com.lamuier.agenT.model.UsageMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class WidgetCalculationsTest {
    @Test fun combinedSummaryIncludesCodexWhenCursorIsMissing() {
        val codex = serviceStatus().copy(indicator = StatusIndicator.Major)
        assertEquals(StatusIndicator.Major, WidgetCalculations.combinedIndicator(null, codex))
        assertNull(WidgetCalculations.combinedIndicator(null, serviceStatus()))
    }

    @Test fun combinedSummaryUsesTheWorseProviderState() {
        assertEquals(StatusIndicator.Critical, WidgetCalculations.combinedIndicator(
            serviceStatus().copy(indicator = StatusIndicator.Minor),
            serviceStatus().copy(indicator = StatusIndicator.Critical)))
        assertEquals(StatusIndicator.None, WidgetCalculations.combinedIndicator(serviceStatus(), serviceStatus()))
    }

    @Test fun componentSlotsAreSharedBetweenBothProviders() {
        val cursor = serviceStatus(components = listOf(
            component("ide", "IDE", ComponentStatus.Operational),
            component("agents", "Cloud Agents", ComponentStatus.Operational)))
        val codex = serviceStatus(components = listOf(
            component("web", "Codex Web", ComponentStatus.Operational),
            component("cli", "CLI", ComponentStatus.Operational)))
        assertEquals(listOf("Cursor · IDE", "Codex · Codex Web", "Cursor · Cloud Agents", "Codex · CLI"),
            WidgetCalculations.combinedComponents(cursor, codex, 4).map { it.first })
        assertEquals(2, WidgetCalculations.combinedComponents(null, codex, 2).size)
        assertEquals(emptyList<Pair<String, ComponentStatus>>(), WidgetCalculations.combinedComponents(cursor, codex, 0))
    }
    @Test
    fun individualProgress_isClampedForRemoteViews() {
        val usage = overview(isTeam = false, totalUsed = 118.4)
        assertEquals(118.4, WidgetCalculations.totalPercent(usage), 0.001)
        assertEquals(100, WidgetCalculations.progress(WidgetCalculations.totalPercent(usage)))
    }

    @Test
    fun teamProgress_usesPlanFallbackLimit() {
        val usage = overview(
            isTeam = true,
            totalUsed = 80.0,
            includedSpend = 30.0,
            limit = 0.0,
            planLimit = 60.0,
        )
        assertEquals(50.0, WidgetCalculations.totalPercent(usage), 0.001)
        assertEquals("\$80.00", WidgetCalculations.totalLabel(usage))
    }

    @Test
    fun invalidPercent_rendersEmptyProgress() {
        assertEquals(0, WidgetCalculations.progress(Double.NaN))
        assertEquals(0, WidgetCalculations.progress(null))
    }

    @Test
    fun operationalPercent_countsHealthyComponents() {
        val status = serviceStatus(
            components = listOf(
                component("ide", "IDE", ComponentStatus.Operational),
                component("agents", "Cloud Agents", ComponentStatus.PartialOutage),
                component("cli", "CLI", ComponentStatus.Operational),
                component("web", "Web", ComponentStatus.DegradedPerformance),
            ),
        )
        assertEquals(50.0, WidgetCalculations.operationalPercent(status), 0.001)
        assertEquals("2/4", WidgetCalculations.summaryChip(status))
        assertEquals(50, WidgetCalculations.progress(WidgetCalculations.operationalPercent(status)))
    }

    @Test
    fun statusBadgeGlyph_matchesOverallIndicator() {
        assertEquals(StatusBadgeGlyph.Ok, WidgetCalculations.statusBadgeGlyph(StatusIndicator.None))
        assertEquals(StatusBadgeGlyph.Warning, WidgetCalculations.statusBadgeGlyph(StatusIndicator.Minor))
        assertEquals(StatusBadgeGlyph.Error, WidgetCalculations.statusBadgeGlyph(StatusIndicator.Major))
        assertEquals(StatusBadgeGlyph.Error, WidgetCalculations.statusBadgeGlyph(StatusIndicator.Critical))
        assertEquals(StatusBadgeGlyph.Maintenance, WidgetCalculations.statusBadgeGlyph(StatusIndicator.Maintenance))
        assertEquals(StatusBadgeGlyph.Unknown, WidgetCalculations.statusBadgeGlyph(null))
    }

    @Test
    fun emptyComponents_renderPlaceholder() {
        val status = serviceStatus()
        assertEquals(0.0, WidgetCalculations.operationalPercent(status), 0.001)
        assertEquals("—", WidgetCalculations.summaryChip(status))
        assertEquals("暂无事件", WidgetCalculations.incidentHeadline(status))
    }

    @Test
    fun highlightedComponents_preferDegradedThenFillOperational() {
        val status = serviceStatus(
            components = listOf(
                component("ide", "IDE", ComponentStatus.Operational),
                component("agents", "Cloud Agents", ComponentStatus.PartialOutage),
                component("cli", "CLI", ComponentStatus.Operational),
                component("web", "Web", ComponentStatus.DegradedPerformance),
            ),
        )
        assertEquals(
            listOf("Cloud Agents" to ComponentStatus.PartialOutage, "Web" to ComponentStatus.DegradedPerformance),
            WidgetCalculations.highlightedComponents(status, 2),
        )
        assertEquals(
            listOf(
                "Cloud Agents" to ComponentStatus.PartialOutage,
                "Web" to ComponentStatus.DegradedPerformance,
                "IDE" to ComponentStatus.Operational,
            ),
            WidgetCalculations.highlightedComponents(status, 3),
        )
    }

    @Test
    fun incidentHeadline_prefersActiveThenMaintenanceThenRecent() {
        val active = incident("inc-active", "Agents delayed")
        val maintenance = incident("mnt-1", "Scheduled CLI window")
        val recent = incident(
            id = "inc-old",
            name = "Brief IDE blip",
            affected = listOf("IDE", "Web"),
            updatedAt = "2026-08-18T20:15:48.726Z",
            resolvedAt = "2026-08-18T20:15:48.707Z",
        )
        assertEquals("Agents delayed", WidgetCalculations.incidentHeadline(serviceStatus(active = listOf(active))))
        assertEquals(
            "Scheduled CLI window",
            WidgetCalculations.incidentHeadline(serviceStatus(maintenance = listOf(maintenance))),
        )
        assertEquals(
            "近期 IDE · 08/18 20:15 GMT",
            WidgetCalculations.incidentHeadline(
                serviceStatus(recent = listOf(recent)),
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun incidentHeadline_recentOmitsFaultReason() {
        val recent = incident(
            id = "inc-old",
            name = "Grok Bot connector delays",
            affected = listOf("Grok Bot"),
            createdAt = "2026-08-18T18:46:26.341Z",
            resolvedAt = "2026-08-18T20:15:48.707Z",
        )
        val headline = WidgetCalculations.incidentHeadline(
            serviceStatus(recent = listOf(recent)),
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("近期 Grok Bot · 08/18 20:15 GMT", headline)
        assertFalse(headline.contains("connector delays"))
        assertFalse(headline.contains(recent.name))
    }

    @Test
    fun incidentHeadline_recentFallsBackToTimeWithoutModule() {
        val recent = incident(
            id = "inc-old",
            name = "Brief IDE blip",
            createdAt = "2026-08-18T18:46:26.341Z",
        )
        assertEquals(
            "近期 08/18 18:46 GMT",
            WidgetCalculations.incidentHeadline(
                serviceStatus(recent = listOf(recent)),
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    private fun overview(
        isTeam: Boolean,
        totalUsed: Double,
        includedSpend: Double = 0.0,
        limit: Double = 0.0,
        planLimit: Double = 0.0,
    ) = AgenTOverview(
        accountId = 1,
        alias = "test",
        isTeam = isTeam,
        plan = PlanInfo(null, null, planLimit, null),
        billingCycle = BillingCycle(null, null),
        usage = UsageMetrics(
            totalUsed = totalUsed,
            totalFormat = if (isTeam) TotalFormat.Dollars else TotalFormat.Percent,
            totalSpendDollars = totalUsed,
            includedSpendDollars = includedSpend,
            bonusSpendDollars = 0.0,
            limitDollars = limit,
            remainingDollars = 0.0,
            autoPercentUsed = null,
            apiPercentUsed = null,
            displayMessage = null,
            remainingBonus = false,
        ),
        credits = Credits(0.0, 0.0, 0.0),
        onDemand = null,
        subscription = Subscription(null, null),
        fetchedAt = "",
        fromCache = false,
        cacheAgeSeconds = 0,
    )

    private fun serviceStatus(
        components: List<StatusComponent> = emptyList(),
        active: List<StatusIncident> = emptyList(),
        maintenance: List<StatusIncident> = emptyList(),
        recent: List<StatusIncident> = emptyList(),
    ) = ServiceStatus(
        description = "",
        indicator = StatusIndicator.None,
        pageUpdatedAt = null,
        pageUrl = "https://status.cursor.com",
        components = components,
        activeIncidents = active,
        scheduledMaintenances = maintenance,
        recentIncidents = recent,
        fetchedAt = "2026-08-19 12:00:00",
        fromCache = false,
    )

    private fun component(id: String, name: String, status: ComponentStatus) = StatusComponent(
        id = id,
        name = name,
        status = status,
        position = 0,
    )

    private fun incident(
        id: String,
        name: String,
        affected: List<String> = emptyList(),
        createdAt: String? = null,
        updatedAt: String? = null,
        resolvedAt: String? = null,
    ) = StatusIncident(
        id = id,
        name = name,
        status = "investigating",
        impact = "major",
        createdAt = createdAt,
        updatedAt = updatedAt,
        resolvedAt = resolvedAt,
        scheduledFor = null,
        scheduledUntil = null,
        shortlink = null,
        affectedComponents = affected,
        updates = emptyList(),
    )
}
