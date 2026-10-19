package com.lamuier.agenT.providers

import android.content.Context
import java.security.KeyStore

/** Drops the retired Antigravity credential store without contacting any service. */
internal object RetiredProviderCleanup {
    fun purge(context: Context) {
        context.getSharedPreferences("official_usage_antigravity_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val usage = context.getSharedPreferences("usage-page-v1", Context.MODE_PRIVATE)
        if (usage.getString("provider", null) == "antigravity") usage.edit().remove("provider").commit()
        val pages = context.getSharedPreferences("widget-source-pages-v1", Context.MODE_PRIVATE)
        val stale = pages.all.filterValues { it == "antigravity" }.keys
        if (stale.isNotEmpty()) pages.edit().apply { stale.forEach(::remove) }.commit()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private const val KEY_ALIAS = "agenT.official.antigravity.v1"
}
