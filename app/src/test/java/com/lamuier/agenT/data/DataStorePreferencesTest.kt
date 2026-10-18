package com.lamuier.agenT.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStorePreferencesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testDataStoreWriteAndRead() = runBlocking {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppDataStoreKeys.THEME_MODE] = "dark"
            prefs[AppDataStoreKeys.HAPTIC_FEEDBACK] = true
        }

        val prefs = context.appPreferencesDataStore.data.first()
        assertEquals("dark", prefs[AppDataStoreKeys.THEME_MODE])
        assertTrue(prefs[AppDataStoreKeys.HAPTIC_FEEDBACK] == true)
    }
}
