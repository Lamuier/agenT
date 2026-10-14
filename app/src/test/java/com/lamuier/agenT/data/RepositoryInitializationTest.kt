package com.lamuier.agenT.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.codex.CodexQuotaRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryInitializationTest {
    @Test fun constructingRepositoriesDoesNotReadAccountsRunMigrationsOrLoadKeystore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("cursor_direct_accounts_v2", 0)
        preferences.edit().clear().putString("accounts", "corrupt fixture").commit()
        val cursor = CursorRepository(context)
        assertNotNull(cursor)
        assertNotNull(CodexQuotaRepository(context))
        assertFalse(preferences.contains("migrated_from_pulse_v1"))
        assertTrue(runCatching { cursor.listAccounts() }.isFailure)
        preferences.edit().clear().commit()
    }
}
