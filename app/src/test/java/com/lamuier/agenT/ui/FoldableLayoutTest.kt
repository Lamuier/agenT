package com.lamuier.agenT.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class FoldableLayoutTest {
    private val window = Rect(0f, 0f, 1000f, 800f)
    @Test fun bookPostureUsesBothSidesOfAZeroWidthCrease() {
        val plan = paneLayout(window, listOf(Rect(500f, 0f, 500f, 800f)), 320f, 160f, 16f)
        assertEquals(Rect(0f, 0f, 492f, 800f), plan.first)
        assertEquals(Rect(508f, 0f, 1000f, 800f), plan.second)
    }
    @Test fun tabletopUsesAreasAboveAndBelowTheHinge() {
        val plan = paneLayout(window, listOf(Rect(0f, 390f, 1000f, 410f)), 320f, 160f, 16f)
        assertEquals(Rect(0f, 0f, 1000f, 382f), plan.first)
        assertEquals(Rect(0f, 418f, 1000f, 800f), plan.second)
    }
    @Test fun contentOffsetDoesNotShiftThePhysicalHinge() {
        val origin = Offset(80f, 64f)
        val localFold = Rect(500f, 0f, 520f, 900f).translate(-origin)
        val plan = paneLayout(Rect(0f, 0f, 920f, 756f), listOf(localFold), 320f, 160f, 16f)
        assertEquals(492f, plan.first.right + origin.x)
        assertEquals(528f, plan.second!!.left + origin.x)
    }
    @Test fun largeFontFallsBackToTheLargerUnobstructedArea() {
        val plan = paneLayout(window, listOf(Rect(400f, 0f, 420f, 800f)), 640f, 320f, 16f)
        assertNull(plan.second)
        assertEquals(Rect(428f, 0f, 1000f, 800f), plan.first)
    }
    @Test fun shortTabletopPaneFallsBackWithoutCrossingCrease() {
        val plan = paneLayout(window, listOf(Rect(0f, 100f, 1000f, 100f)), 320f, 160f, 16f)
        assertNull(plan.second)
        assertEquals(Rect(0f, 108f, 1000f, 800f), plan.first)
    }
    @Test fun outsideAndEdgeFeaturesDoNotRemoveUsableContent() {
        for (fold in listOf(Rect(-20f, 0f, 0f, 800f), Rect(1000f, 0f, 1020f, 800f), Rect(500f, 900f, 500f, 1000f))) {
            assertEquals(window, safePane(window, listOf(fold), 8f))
        }
    }
    @Test fun partiallyClippedOpaqueHingeStillRemovesOccludedPixels() {
        assertEquals(Rect(28f, 0f, 1000f, 800f),
            safePane(window, listOf(Rect(-20f, 0f, 20f, 800f)), 8f))
    }
    @Test fun multipleSeparatorsNeverPlaceContentOnAnotherHinge() {
        val folds = listOf(Rect(300f, 0f, 320f, 800f), Rect(650f, 0f, 670f, 800f))
        val plan = paneLayout(window, folds, 320f, 160f, 16f)
        assertNull(plan.second)
        assertEquals(Rect(678f, 0f, 1000f, 800f), plan.first)
    }
    @Test fun tabletPanesAllocateSpaceByTheirContentWithoutMovingPhysicalHinges() {
        val usage = paneLayout(window, emptyList(), 320f, 160f, 16f, primaryFraction = 0.65f)
        assertTrue(usage.first.width > usage.second!!.width)
        assertTrue(usage.second!!.width >= 320f)
        val settings = paneLayout(window, emptyList(), 240f, 160f, 16f, preferredPrimaryWidth = 280f)
        assertEquals(280f, settings.first.width)
        assertTrue(settings.second!!.width > settings.first.width * 2)
        val book = paneLayout(window, listOf(Rect(500f, 0f, 500f, 800f)), 320f, 160f, 16f,
            primaryFraction = 0.65f, preferredPrimaryWidth = 280f)
        assertEquals(492f, book.first.right)
        assertEquals(508f, book.second!!.left)
    }
    @Test fun compactWindowsStaySingleAndWideWindowsShareSpace() {
        assertFalse(paneLayout(Rect(0f, 0f, 360f, 800f), emptyList(), 320f, 160f, 16f).paired)
        assertTrue(paneLayout(window, emptyList(), 320f, 160f, 16f).paired)
        assertFalse(paneLayout(window, emptyList(), 320f, 160f, 16f, false).paired)
    }
}
