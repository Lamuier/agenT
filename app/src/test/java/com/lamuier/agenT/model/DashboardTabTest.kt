package com.lamuier.agenT.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTabTest {
    @Test
    fun fromWidgetLaunch_prefersExplicitTabId() {
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch("status", "mini"))
        assertEquals(DashboardTab.Overview, DashboardTab.fromWidgetLaunch("overview", "statustall"))
        assertEquals(DashboardTab.Tasks, DashboardTab.fromWidgetLaunch(" TASKS ", null))
    }

    @Test
    fun removedBillingLinksOpenUsageButAreNotOrderEntries() {
        assertEquals(DashboardTab.Usage, DashboardTab.fromWidgetLaunch("billing", null))
        assertEquals(DashboardTab.Usage, DashboardTab.fromWidgetLaunch(" BILLING ", "statusmini"))
        assertEquals(null, DashboardTab.fromId("billing"))
    }

    @Test
    fun fromWidgetLaunch_fallsBackToWidgetKind() {
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch(null, "statusmini"))
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch("", "StatusTall"))
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch(null, "statuswide"))
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch(null, "StatusSquare"))
        assertEquals(DashboardTab.Status, DashboardTab.fromWidgetLaunch(null, "statusmedium"))
        assertEquals(DashboardTab.Overview, DashboardTab.fromWidgetLaunch(null, "mini"))
        assertEquals(DashboardTab.Overview, DashboardTab.fromWidgetLaunch(null, "tall"))
        assertEquals(null, DashboardTab.fromWidgetLaunch(null, "unknown"))
        assertEquals(null, DashboardTab.fromWidgetLaunch(null, null))
    }

    @Test
    fun resolveOrder_nullOrBlankUsesDefault() {
        assertEquals(DashboardTab.DEFAULT_ORDER, DashboardTab.resolveOrder(null as String?))
        assertEquals(DashboardTab.DEFAULT_ORDER, DashboardTab.resolveOrder(""))
        assertEquals(DashboardTab.DEFAULT_ORDER, DashboardTab.resolveOrder("   ,  ,"))
    }

    @Test
    fun resolveOrder_keepsCustomPrefixAndAppendsMissing() {
        val order = DashboardTab.resolveOrder("status,tasks")
        assertEquals(
            listOf(
                DashboardTab.Status,
                DashboardTab.Tasks,
                DashboardTab.Overview,
                DashboardTab.Usage,
                DashboardTab.Resets,
            ),
            order,
        )
    }

    @Test
    fun resolveOrder_ignoresUnknownDuplicatesAndWhitespace() {
        val order = DashboardTab.resolveOrder(" TASKS , status , tasks , evil,overview ")
        assertEquals(
            listOf(
                DashboardTab.Tasks,
                DashboardTab.Status,
                DashboardTab.Overview,
                DashboardTab.Usage,
                DashboardTab.Resets,
            ),
            order,
        )
    }

    @Test
    fun serialize_roundTripsNormalizedOrder() {
        val custom = listOf(DashboardTab.Status, DashboardTab.Overview)
        val serialized = DashboardTab.serialize(custom)
        assertEquals("status,overview,usage,tasks,resets", serialized)
        assertEquals(DashboardTab.resolveOrder(serialized), DashboardTab.resolveOrder(custom.map { it.id }))
    }

    @Test
    fun move_swapsWithinBoundsAndNoopsAtEnds() {
        val start = DashboardTab.DEFAULT_ORDER
        assertEquals(start, DashboardTab.move(start, 0, -1))
        assertEquals(start, DashboardTab.move(start, start.lastIndex, 1))
        assertEquals(start, DashboardTab.move(start, -1, 1))

        assertEquals(
            listOf(
                DashboardTab.Usage,
                DashboardTab.Overview,
                DashboardTab.Tasks,
                DashboardTab.Resets,
                DashboardTab.Status,
            ),
            DashboardTab.move(start, 0, 1),
        )
        assertEquals(
            listOf(
                DashboardTab.Overview,
                DashboardTab.Tasks,
                DashboardTab.Usage,
                DashboardTab.Resets,
                DashboardTab.Status,
            ),
            DashboardTab.move(start, 2, -1),
        )
    }

    @Test
    fun savedFourTabOrderInsertsTasksRightAfterUsage() {
        assertEquals(DashboardTab.DEFAULT_ORDER,
            DashboardTab.resolveSavedOrder("overview,usage,resets,status"))
        assertEquals(listOf(DashboardTab.Status, DashboardTab.Usage, DashboardTab.Tasks,
            DashboardTab.Resets, DashboardTab.Overview),
            DashboardTab.resolveSavedOrder("status,usage,resets,overview"))
        assertEquals(DashboardTab.Resets, DashboardTab.fromId("resets"))
    }

    @Test
    fun savedSixTabCustomOrderDropsBillingAndPreservesOtherPositions() {
        val order = listOf(DashboardTab.Tasks, DashboardTab.Status, DashboardTab.Usage,
            DashboardTab.Resets, DashboardTab.Overview)
        assertEquals(order, DashboardTab.resolveSavedOrder("tasks,status,usage,resets,billing,overview"))
        assertEquals(order, DashboardTab.resolveSavedOrder(DashboardTab.serialize(order)))
        assertEquals(DashboardTab.DEFAULT_ORDER, DashboardTab.resolveSavedOrder(null))
    }

    @Test
    fun legacyBillingAtFrontDoesNotMoveUsageOrReappearAfterSaving() {
        val order = DashboardTab.resolveSavedOrder(" BILLING , status , overview , billing , usage , tasks , resets ")
        assertEquals(listOf(DashboardTab.Status, DashboardTab.Overview, DashboardTab.Usage,
            DashboardTab.Tasks, DashboardTab.Resets), order)
        assertEquals("status,overview,usage,tasks,resets", DashboardTab.serialize(order))
    }

    @Test
    fun scopesIdentifySharedAndProviderOnlyCapabilities() {
        assertEquals(listOf(DashboardTab.Overview, DashboardTab.Usage,
            DashboardTab.Tasks, DashboardTab.Resets, DashboardTab.Status), DashboardTab.DEFAULT_ORDER)
        assertEquals(listOf(DashboardTab.Overview, DashboardTab.Usage, DashboardTab.Tasks, DashboardTab.Status),
            DashboardTab.entries.filter { it.scope == DashboardTabScope.Common })
        assertEquals(emptyList<DashboardTab>(),
            DashboardTab.entries.filter { it.scope == DashboardTabScope.Cursor })
        assertEquals(listOf(DashboardTab.Resets), DashboardTab.entries.filter { it.scope == DashboardTabScope.Codex })
    }
}
