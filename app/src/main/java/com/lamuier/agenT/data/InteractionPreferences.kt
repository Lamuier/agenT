package com.lamuier.agenT.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InteractionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("agentt_interaction_v1", Context.MODE_PRIVATE)
    private val enabled = MutableStateFlow(preferences.getBoolean("haptic_feedback_enabled", true))
    val hapticFeedbackEnabled = enabled.asStateFlow()

    fun setHapticFeedbackEnabled(value: Boolean) {
        preferences.edit().putBoolean("haptic_feedback_enabled", value).apply()
        enabled.value = value
        (appContext as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.launch {
            runCatching {
                appContext.appPreferencesDataStore.edit { it[AppDataStoreKeys.HAPTIC_FEEDBACK] = value }
            }
        }
    }

    companion object {
        @Volatile private var instance: InteractionPreferences? = null
        fun get(context: Context): InteractionPreferences = instance ?: synchronized(this) {
            instance ?: InteractionPreferences(context).also { instance = it }
        }
    }
}
