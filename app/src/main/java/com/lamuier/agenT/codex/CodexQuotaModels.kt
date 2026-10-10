package com.lamuier.agenT.codex

import androidx.compose.runtime.Immutable
import com.lamuier.agenT.model.ServiceStatus

@Immutable
data class CodexCredentialInfo(
    val expired: Boolean,
    val invalid: Boolean,
    val updatedAt: String,
    val accountId: String = "",
)

@Immutable
data class CodexQuotaWindow(
    val usedPercent: Double,
    val limitWindowSeconds: Long,
    val resetAfterSeconds: Long?,
    val resetAtEpochSeconds: Long?,
)

@Immutable
data class CodexQuotaOverview(
    val planType: String?,
    val primary: CodexQuotaWindow?,
    val secondary: CodexQuotaWindow?,
    val additional: List<CodexQuotaWindow>,
    val resetCreditsAvailable: Int?,
    val fetchedAt: String,
    val fromCache: Boolean = false,
    val cacheAgeSeconds: Int = 0,
    val resetCredits: CodexResetCredits? = null,
)

@Immutable
data class CodexQuotaUiState(
    val credential: CodexCredentialInfo? = null,
    val usage: CodexQuotaOverview? = null,
    val booting: Boolean = true,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val serviceStatus: ServiceStatus? = null,
    val loadingStatus: Boolean = true,
    val refreshingStatus: Boolean = false,
    val statusError: String? = null,
    val resetLedger: CodexResetLedger? = null,
    val resetError: String? = null,
    val resetSaving: Boolean = false,
    val publicResets: CodexPublicResetSnapshot? = null,
    val refreshingPublicResets: Boolean = false,
    val publicResetsError: String? = null,
)
