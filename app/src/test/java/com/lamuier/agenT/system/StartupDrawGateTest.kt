package com.lamuier.agenT.system

import android.content.Context
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupDrawGateTest {
    private fun root() = FrameLayout(ApplicationProvider.getApplicationContext<Context>())

    @Test fun partialLocalContentDoesNotDrawBeforeCacheRestorationIsPublished() {
        val root = root()
        val gate = StartupDrawGate(root)
        assertTrue(root.viewTreeObserver.dispatchOnPreDraw())
        root.addView(TextView(root.context).apply { text = "Cursor cached quota" })
        assertTrue(root.viewTreeObserver.dispatchOnPreDraw())
        root.addView(TextView(root.context).apply { text = "Codex cached quota" })
        gate.release()
        assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
    }

    @Test fun laterRefreshesAndRepeatedReleaseDoNotBlockDrawingAgain() {
        val root = root()
        val gate = StartupDrawGate(root)
        gate.release()
        repeat(3) {
            root.invalidate()
            gate.release()
            assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
        }
    }

    @Test fun disposingStartupGatePreservesOtherPreDrawListeners() {
        val root = root()
        val gate = StartupDrawGate(root)
        var otherListenerCalls = 0
        val other = ViewTreeObserver.OnPreDrawListener { otherListenerCalls++; false }
        root.viewTreeObserver.addOnPreDrawListener(other)
        gate.release()
        assertTrue(root.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(1, otherListenerCalls)
        root.viewTreeObserver.removeOnPreDrawListener(other)
        assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
    }
}
