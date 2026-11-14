package com.lamuier.agenT.providers

import android.content.Context
import java.security.KeyStore

/** Drops the retired Antigravity credential store without contacting any service. */
internal object RetiredProviderCleanup {
    private const val KEY_PURGED = "retired_antigravity_purged_v1"

    fun purge(context: Context) {
        val retired = context.getSharedPreferences("official_usage_antigravity_v1", Context.MODE_PRIVATE)
        val usage = context.getSharedPreferences("usage-page-v1", Context.MODE_PRIVATE)
        val pages = context.getSharedPreferences("widget-source-pages-v1", Context.MODE_PRIVATE)
        val hasRetired = retired.all.isNotEmpty()
        val hasUsageProvider = usage.getString("provider", null) == "antigravity"
        val stalePages = pages.all.filterValues { it == "antigravity" }.keys
        if (!hasRetired && !hasUsageProvider && stalePages.isEmpty() && usage.getBoolean(KEY_PURGED, false)) return
        if (hasRetired) retired.edit().clear().commit()
        usage.edit().apply {
            if (hasUsageProvider) remove("provider")
            putBoolean(KEY_PURGED, true)
        }.commit()
        if (stalePages.isNotEmpty()) pages.edit().apply { stalePages.forEach(::remove) }.commit()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private const val KEY_ALIAS = "agenT.official.antigravity.v1"
}
