package com.lamuier.agenT.util

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AssistantPresentationTest {
    @Test fun phonesStaySingleColumnRegardlessOfHeight() {
        listOf(320f, 360f, 393f, 411f, 480f).forEach {
            assertFalse("phone width=$it", AssistantPresentation.useTwoColumns(it, 1f))
        }
        assertTrue(AssistantPresentation.useTwoColumns(720f, 1f))
        assertFalse(AssistantPresentation.useTwoColumns(720f, 1.3f))
    }

    @Test fun quotaNumbersStayReadableWithoutExtraDecimalPlaces() {
        assertEquals("99.7%", AssistantPresentation.percent(99.74))
        assertEquals("37%", AssistantPresentation.percent(37.0))
        assertEquals("123%", AssistantPresentation.percent(123.0))
        assertEquals("—", AssistantPresentation.percent(Double.NaN))
        assertEquals("Plus", AssistantPresentation.plan("plus"))
    }

    @Test fun resetTimeUsesLocalDateAndOmitsRepeatedDateForToday() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-06T18:53:00Z")
        assertEquals("07:04", AssistantPresentation.resetTime(Instant.parse("2026-10-06T23:04:00Z").epochSecond, zone, now))
        assertEquals("10/11 23:46", AssistantPresentation.resetTime(Instant.parse("2026-10-11T15:46:00Z").epochSecond, zone, now))
    }
}
