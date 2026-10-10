package com.lamuier.agenT.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** In-memory interface fake: preference persistence tests do not need Android or an emulator. */
class NotificationPreferencesTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        val preferences: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString", "getStringSet" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> values.toMap()
                "edit" -> editor()
                "toString" -> "MemoryPreferences"
                else -> error("Unexpected preference method: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putString", "putStringSet" -> {
                        updates[args!![0] as String] = args[1]
                        editor
                    }
                    "apply", "commit" -> {
                        values.putAll(updates)
                        if (method.name == "commit") true else null
                    }
                    "toString" -> "MemoryEditor"
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    @Test fun missingStyleDefaultsToFormalWithoutEnablingAnyNotification() {
        val prefs = NotificationPreferences(MemoryPreferences().preferences)
        assertFalse(prefs.read().catLanguageEnabled)
        prefs.setCatLanguageEnabled(true)
        assertTrue(prefs.settings.value.catLanguageEnabled)
        assertFalse(prefs.read().liveUpdatesEnabled)
        assertFalse(prefs.read().thresholdRemindersEnabled)
        assertFalse(prefs.read().codexResetRemindersEnabled)
    }

    @Test fun stylePersistsAcrossInstancesAndPreservesExistingPreferencesAndDedupe() {
        val storage = MemoryPreferences()
        val prefs = NotificationPreferences(storage.preferences)
        prefs.setLiveUpdatesEnabled(true)
        prefs.setThresholdRemindersEnabled(true)
        prefs.setCodexResetRemindersEnabled(true)
        prefs.setPercentDisplayMode(PercentDisplayMode.Remaining)
        prefs.markThresholdReminded("existing-cycle", 80)
        prefs.markResetNotified("existing-reset")
        val original = prefs.read()
        prefs.setCatLanguageEnabled(true)
        assertEquals(original.copy(catLanguageEnabled = true), prefs.settings.value)
        val reopened = NotificationPreferences(storage.preferences)
        assertTrue(reopened.read().catLanguageEnabled)
        assertEquals(setOf(80), reopened.remindedThresholds("existing-cycle"))
        assertTrue(reopened.resetAlreadyNotified("existing-reset"))
        reopened.setCatLanguageEnabled(false)
        assertEquals(original, reopened.read())
        assertEquals(setOf(80), reopened.remindedThresholds("existing-cycle"))
        assertTrue(reopened.resetAlreadyNotified("existing-reset"))
    }
}
