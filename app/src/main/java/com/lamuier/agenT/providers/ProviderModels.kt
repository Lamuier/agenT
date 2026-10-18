package com.lamuier.agenT.providers

import androidx.compose.runtime.Immutable
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.TokenProvider
import com.lamuier.agenT.network.OfficialStatusProvider
import java.math.BigDecimal

enum class UsageProvider(val id: String, val title: String, val tokenProvider: TokenProvider,
    val statusProvider: OfficialStatusProvider?, val consoleUrl: String) {
    DeepSeek("deepseek", "DeepSeek", TokenProvider.DeepSeek, OfficialStatusProvider.DeepSeek, "https://platform.deepseek.com/"),
    KimiCode("kimi_code", "Kimi Code", TokenProvider.KimiCode, OfficialStatusProvider.KimiCode, "https://www.kimi.com/code/console"),
    Antigravity("antigravity", "Antigravity", TokenProvider.Antigravity, null, "https://www.antigravity.google/docs/cli/commands/usage"),
}

enum class KimiRegion(val id: String, val baseUrl: String) {
    China("cn", "https://api.kimi.com/coding/v1"),
    Global("global", "https://api.kimi.ai/coding/v1");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: China }
}

@Immutable
data class ProviderCredentialInfo(val revision: String, val region: KimiRegion = KimiRegion.China)
@Immutable
data class MoneyBalance(val currency: String, val total: BigDecimal?, val granted: BigDecimal?, val toppedUp: BigDecimal?)
@Immutable
data class QuotaWindow(val id: String, val usedRatio: BigDecimal?, val resetAt: String?,
    val durationMinutes: Long? = null, val inconsistent: Boolean = false,
    val label: String? = null, val disabled: Boolean = false)
@Immutable
data class BoosterWallet(val remaining: BigDecimal?, val total: BigDecimal?, val currency: String?,
    val monthlyLimit: BigDecimal?, val monthlyUsed: BigDecimal?, val limitEnabled: Boolean?)

/** Balance sufficiency is independent of the public service status. Missing values stay absent. */
@Immutable
data class ProviderUsage(val provider: UsageProvider, val fetchedAt: String, val fromCache: Boolean = false,
    val available: Boolean? = null, val balances: List<MoneyBalance> = emptyList(),
    val windows: List<QuotaWindow> = emptyList(), val booster: BoosterWallet? = null)
@Immutable
data class ProviderState(val credential: ProviderCredentialInfo? = null, val usage: ProviderUsage? = null,
    val serviceStatus: ServiceStatus? = null, val loading: Boolean = false, val saving: Boolean = false,
    val loadingStatus: Boolean = false, val error: String? = null, val statusError: String? = null)
@Immutable
data class ProvidersUiState(val booting: Boolean = true,
    val sources: Map<UsageProvider, ProviderState> = UsageProvider.entries.associateWith { ProviderState() }) {
    operator fun get(provider: UsageProvider) = sources.getValue(provider)
    val busy get() = booting || sources.values.any { it.loading || it.loadingStatus || it.saving }
}
