package com.lamuier.agenT.ui

import org.junit.Assert.*
import org.junit.Test

class LoadFailureTrackerTest {
    @Test fun persistentFailureNotifiesOnceAndRetryCanNotifyAgain() {
        val tracker = LoadFailureTracker()
        assertTrue(tracker.shouldNotify(setOf("codex-usage"), false))
        assertFalse(tracker.shouldNotify(setOf("codex-usage"), false))
        assertFalse(tracker.shouldNotify(emptySet(), false))
        assertTrue(tracker.shouldNotify(setOf("codex-usage"), false))
        assertTrue(tracker.shouldNotify(setOf("codex-usage", "cursor-history"), false))
        assertFalse(tracker.shouldNotify(setOf("codex-usage"), false))
    }
    @Test fun sheetsDeferNewFailuresWithoutReplayingPreviouslyShownOnes() {
        val tracker = LoadFailureTracker()
        assertFalse(tracker.shouldNotify(setOf("cursor-usage"), true))
        assertTrue(tracker.shouldNotify(setOf("cursor-usage"), false))
        assertFalse(tracker.shouldNotify(setOf("cursor-usage"), true))
        assertFalse(tracker.shouldNotify(setOf("cursor-usage"), false))
        assertFalse(tracker.shouldNotify(emptySet(), true))
        assertFalse(tracker.shouldNotify(setOf("cursor-usage"), true))
        assertTrue(tracker.shouldNotify(setOf("cursor-usage"), false))
    }
}
