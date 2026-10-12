package com.lamuier.agenT.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.model.AppUiState
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.LocalPulseChartColors
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.StatusPresentation
import com.lamuier.agenT.util.UsageCalculations

@Composable
internal fun UnifiedOverviewTab(state: AppUiState, codex: CodexQuotaUiState, onOpenSettings: () -> Unit, onManageCursor: () -> Unit = onOpenSettings, onManageCodex: () -> Unit = onOpenSettings) {
    val zone = LocalDisplayZone.current
    val fontScale = LocalDensity.current.fontScale
    AdaptiveTabContent {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cursorCard: @Composable (Modifier) -> Unit = { modifier ->
                AssistantOverviewCard("Cursor", state.usage?.plan?.name, state.serviceStatus, modifier) {
                    val account = state.accounts.firstOrNull { it.id == state.selectedAccountId }
                    val usage = state.usage?.takeUnless { account?.tokenExpired == true }
                    when {
                        state.loadingAccounts -> OverviewHint(stringResource(R.string.dashboard_account_loading))
                        account == null -> OverviewConnectionHint(R.string.connection_not_configured, onManageCursor)
                        account.tokenExpired -> OverviewConnectionHint(R.string.connection_token_expired, onManageCursor)
                        usage == null -> OverviewHint(state.error ?: stringResource(R.string.dashboard_loading_usage_body))
                        else -> {
                            val billing = UsageCalculations.billingProgress(usage.billingCycle.start, usage.billingCycle.end,
                                System.currentTimeMillis(), displayZone = zone)
                            val used: @Composable (Modifier) -> Unit = { modifier ->
                                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.overview_cycle_used), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(AssistantPresentation.percent(UsageCalculations.overviewTotalUsage(usage).percent),
                                        style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold,
                                        letterSpacing = (-1).sp, maxLines = 2)
                                }
                            }
                            val remaining: @Composable () -> Unit = {
                                billing?.let {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.overview_cycle_remaining), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(UsageCalculations.formatRemaining(it.remainingMillis, resources = LocalResources.current),
                                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            if (fontScale > 1.3f) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { used(Modifier.fillMaxWidth()); remaining() }
                            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) { used(Modifier.weight(1f)); remaining() }
                            OverviewPoolMeter(stringResource(R.string.overview_pool_own), usage.usage.autoPercentUsed)
                            OverviewPoolMeter(stringResource(R.string.overview_pool_other), usage.usage.apiPercentUsed)
                            OverviewFreshness(usage.fetchedAt, usage.fromCache)
                        }
                    }
                }
            }
            val codexCard: @Composable (Modifier) -> Unit = { modifier ->
                AssistantOverviewCard("Codex", codex.usage?.planType, codex.serviceStatus, modifier) {
                    val credential = codex.credential
                    val usage = codex.usage
                    when {
                        codex.booting -> OverviewHint(stringResource(R.string.codex_status_loading_body))
                        credential == null -> OverviewConnectionHint(R.string.connection_not_configured, onManageCodex)
                        credential.expired || credential.invalid -> OverviewConnectionHint(R.string.connection_token_expired, onManageCodex)
                        usage == null -> OverviewHint(codex.error ?: stringResource(R.string.codex_status_loading_body))
                        else -> {
                            usage.primary?.let { CodexQuotaWindowRow(stringResource(R.string.quota_five_hour_short), it) }
                            usage.secondary?.let { CodexQuotaWindowRow(stringResource(R.string.quota_weekly_short), it) }
                            OverviewFreshness(usage.fetchedAt, usage.fromCache)
                        }
                    }
                }
            }
            if (AssistantPresentation.useTwoColumns(maxWidth.value, fontScale)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    cursorCard(Modifier.weight(1f))
                    codexCard(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    cursorCard(Modifier.fillMaxWidth())
                    codexCard(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantOverviewCard(name: String, plan: String?, status: ServiceStatus?, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPulseChartColors.current
    val statusColor = when (status?.indicator) {
        StatusIndicator.None -> colors.healthy
        StatusIndicator.Minor, StatusIndicator.Maintenance -> colors.warning
        StatusIndicator.Major, StatusIndicator.Critical -> colors.critical
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val resources = LocalResources.current
    val identity: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            AssistantPresentation.plan(plan)?.let {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val serviceState: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
            Text(status?.let { StatusPresentation.compactIndicatorLabel(it.indicator, resources) }
                ?: stringResource(R.string.status_indicator_unknown), style = MaterialTheme.typography.labelSmall,
                color = statusColor, maxLines = 2)
        }
    }
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                identity(); serviceState()
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { identity() }; serviceState()
            }
            content()
        }
    }
}

@Composable
private fun OverviewPoolMeter(title: String, percent: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(AssistantPresentation.percent(percent), style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(progress = { ((percent ?: 0.0).coerceIn(0.0, 100.0) / 100).toFloat() },
            Modifier.fillMaxWidth().height(5.dp), color = when {
                (percent ?: 0.0) >= 100 -> LocalPulseChartColors.current.critical
                (percent ?: 0.0) >= 80 -> LocalPulseChartColors.current.warning
                else -> MaterialTheme.colorScheme.primary
            }, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
private fun OverviewFreshness(updatedAt: String, cached: Boolean) {
    val clock = AssistantPresentation.updatedClock(updatedAt, LocalDisplayZone.current) ?: "—"
    Text(listOfNotNull(stringResource(R.string.codex_updated_at, clock),
        if (cached) stringResource(R.string.overview_cached) else null).joinToString(" · "),
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OverviewHint(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OverviewConnectionHint(message: Int, onOpenSettings: () -> Unit) {
    OverviewHint(stringResource(message))
    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_connections_title)) }
}
