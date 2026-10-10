package com.lamuier.agenT.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetAppearanceTest {
    @Test fun unknownValuesDoNotLookLikeExhaustedQuota() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(WidgetQuotaTone.Unknown, WidgetAppearance.tone(value))
        }
    }
    @Test fun warningBoundariesAreIndependentForEachWindow() {
        assertEquals(WidgetQuotaTone.Normal, WidgetAppearance.tone(89.9))
        assertEquals(WidgetQuotaTone.NearLimit, WidgetAppearance.tone(90.0))
        assertEquals(WidgetQuotaTone.NearLimit, WidgetAppearance.tone(99.9))
        assertEquals(WidgetQuotaTone.Exhausted, WidgetAppearance.tone(100.0))
        assertEquals(WidgetQuotaTone.Exhausted, WidgetAppearance.tone(125.0))
    }
    @Test fun softSurfaceBlendStaysOpaqueAndSupportsLightOrDarkPalettes() {
        assertEquals(0xFFFFFFFF.toInt(), WidgetAppearance.blend(-1, 0, 0f))
        assertEquals(0xFF000000.toInt(), WidgetAppearance.blend(-1, 0, 1f))
        assertEquals(255, WidgetAppearance.blend(0xFF13171E.toInt(), 0xFF60A5FA.toInt(), 0.025f) ushr 24)
    }
    @Test fun unknownAndNormalUseTheirAssignedPaletteColors() {
        assertEquals(2, WidgetAppearance.accent(null, 1, 2, false))
        assertEquals(1, WidgetAppearance.accent(12.0, 1, 2, true))
    }
}
