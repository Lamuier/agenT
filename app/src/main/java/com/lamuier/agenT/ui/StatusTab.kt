package com.lamuier.agenT.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.providers.*
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.LocalPulseChartColors
import com.lamuier.agenT.util.CursorCustomTabs
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.StatusPresentation

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusTab(
    status: ServiceStatus?,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    codexState: CodexQuotaUiState,
    onRetry: () -> Unit,
    onOpenFailed: (String) -> Unit,
    providers: ProvidersUiState = ProvidersUiState(false),
    cursorConfigured: Boolean = false,
    cursorLoading: Boolean = false,
    active: Boolean = true,
    onConfigure: () -> Unit = {},
) {
    val openUrl = rememberOpenStatusUrl(onOpenFailed)
    val feedback = LocalAppFeedback.current
    var requestedProvider by rememberSaveable { mutableStateOf("cursor") }
    val visibleSources = configuredSourceIds(cursorConfigured, codexState.credential != null, providers)
    val provider = requestedProvider.takeIf { it in visibleSources } ?: visibleSources.firstOrNull()
    androidx.compose.runtime.LaunchedEffect(visibleSources, cursorLoading, codexState.booting, providers.booting) {
        if (!cursorLoading && !codexState.booting && !providers.booting && provider != null) requestedProvider = provider
    }
    val destination by com.lamuier.agenT.widget.WidgetNavigation.destination.collectAsState()
    androidx.compose.runtime.LaunchedEffect(destination, active, visibleSources, cursorLoading, codexState.booting, providers.booting) {
        val target = destination
        if (target?.tab == com.lamuier.agenT.model.DashboardTab.Status && active && !cursorLoading && !codexState.booting && !providers.booting) {
            requestedProvider = target.source.takeIf { it in visibleSources } ?: visibleSources.firstOrNull() ?: requestedProvider
            com.lamuier.agenT.widget.WidgetNavigation.consume(target)
        }
    }
    val summaryScroll = androidx.compose.foundation.rememberScrollState()
    val cursorScroll = androidx.compose.foundation.rememberScrollState()
    val codexScroll = androidx.compose.foundation.rememberScrollState()
    val deepSeekScroll = androidx.compose.foundation.rememberScrollState()
    val kimiScroll = androidx.compose.foundation.rememberScrollState()
    val detailScroll = when (provider) { "deepseek" -> deepSeekScroll; "kimi_code" -> kimiScroll; "codex" -> codexScroll; else -> cursorScroll }
    val summary: @Composable () -> Unit = {
        Text(stringResource(R.string.status_services_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
        if ("cursor" in visibleSources) StatusOverviewTile("Cursor", status, loading, Modifier.fillMaxWidth())
        if ("codex" in visibleSources) StatusOverviewTile("Codex", codexState.serviceStatus, codexState.loadingStatus, Modifier.fillMaxWidth())
        UsageProvider.entries.filter { it.id in visibleSources }.forEach { source -> StatusOverviewTile(source.title, providers[source].serviceStatus,
            providers[source].loadingStatus, Modifier.fillMaxWidth()) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cursor" to "Cursor", "codex" to "Codex / OpenAI",
                "deepseek" to "DeepSeek", "kimi_code" to "Kimi Code").filter { it.first in visibleSources }.forEach { (id, label) ->
                FilterChip(selected = provider == id,
                    onClick = { if (provider != id) { requestedProvider = id; feedback.selection() } },
                    label = { Text(label) }, modifier = Modifier.testTag("status-provider-$id"))
            }
        }
    }
    val details: @Composable (Boolean) -> Unit = { compact ->
        if (provider == null) ConfigureSourcesCard(onConfigure)
        else if (provider in listOf("deepseek", "kimi_code")) {
            val source = UsageProvider.entries.first { it.id == provider }
            val current = providers[source]
            StatusProviderPane(false, current.serviceStatus, current.loadingStatus, current.loadingStatus,
                current.statusError, compact, onRetry, openUrl, source.title,
                stringResource(if (source == UsageProvider.DeepSeek) R.string.provider_status_deepseek else R.string.provider_status_kimi))
        } else {
            val codex = provider == "codex"
            StatusProviderPane(codex, if (codex) codexState.serviceStatus else status,
                if (codex) codexState.loadingStatus else loading,
                if (codex) codexState.refreshingStatus else refreshing,
                if (codex) codexState.statusError else error, compact, onRetry, openUrl)
        }
    }
    AdaptivePaneLayout(minPaneWidthDp = SummaryPaneWidthDp, preferredPrimaryWidthDp = SummaryPaneWidthDp,
        maxContentWidthDp = 1440f,
        primary = { Box(Modifier.fillMaxSize().testTag("status-summary-pane")) { AdaptiveTabContent(scrollState = summaryScroll) { summary() } } },
        secondary = { AdaptiveTabContent(scrollState = detailScroll) { compact -> details(compact) } },
        single = { AdaptiveTabContent(scrollState = detailScroll) { compact -> summary(); details(compact) } },
    )
}

@Composable
private fun StatusProviderPane(codex: Boolean, selectedStatus: ServiceStatus?, selectedLoading: Boolean,
    selectedRefreshing: Boolean, selectedError: String?, compact: Boolean,
    onRetry: () -> Unit, openUrl: (String) -> Unit, title: String? = null, supporting: String? = null) {
        SectionHeading(
            icon = Icons.Outlined.Code,
            title = title ?: stringResource(if (codex) R.string.codex_official_status_title else R.string.agent_service_cursor_title),
            supporting = supporting ?: stringResource(if (codex) R.string.codex_official_status_description else R.string.cursor_status_section_supporting),
        )
        when {
            selectedStatus == null && (selectedLoading || selectedRefreshing) -> ProviderStateCard(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.status_loading_title),
                description = stringResource(R.string.status_loading_body),
                loading = true,
            )
            selectedStatus == null -> ProviderStateCard(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.status_unavailable_title),
                description = stringResource(R.string.network_retry_hint),
                primaryActionLabel = stringResource(R.string.action_reload),
                onPrimaryAction = onRetry,
            )
            else -> ServiceStatusContent(
                status = selectedStatus.copy(fromCache = selectedStatus.fromCache || selectedError != null),
                openUrl = openUrl,
                compact = compact,
            )
        }
}

@Composable
private fun StatusOverviewTile(name: String, status: ServiceStatus?, loading: Boolean, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(status?.let { StatusPresentation.compactIndicatorLabel(it.indicator, LocalContext.current.resources) }
                ?: stringResource(if (loading) R.string.status_loading_title else R.string.status_unavailable_title),
                color = status?.let { indicatorColor(it.indicator) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
            status?.let { StatusFreshnessRow(it, showRefreshHint = false) }
        }
    }
}

@Composable
private fun ServiceStatusContent(
    status: ServiceStatus,
    openUrl: (String) -> Unit,
    compact: Boolean,
) {
    val accent = indicatorColor(status.indicator)
    if (status.partialComponents) {
        Text(stringResource(R.string.status_components_partial), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    OverallStatusHero(
        status = status,
        accent = accent,
        compact = compact,
    )
        SectionHeading(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.status_components_title),
            supporting = stringResource(R.string.status_components_supporting),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(if (compact) 6.dp else 8.dp)) {
                val components = androidx.compose.runtime.remember(status.components) { status.components.sortedBy {
                    if (it.name.contains("Codex", true) || it.name == "CLI" || it.name == "VS Code extension") 0 else 1
                } }
                val columns = if (maxWidth >= (480 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    components.chunked(columns).forEach { row ->
                        if (columns == 1) ComponentRow(row.first().name, row.first().status)
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { component ->
                                ComponentTile(component.name, component.status, Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (status.activeIncidents.isNotEmpty()) {
            SectionHeading(
                icon = Icons.Outlined.ReportProblem,
                title = stringResource(R.string.status_current_events),
                supporting = stringResource(R.string.status_active_count, status.activeIncidents.size),
            )
            status.activeIncidents.forEach { incident ->
                IncidentCard(incident, compact, openUrl)
            }
        }

        if (status.scheduledMaintenances.isNotEmpty()) {
            SectionHeading(
                icon = Icons.Outlined.Build,
                title = stringResource(R.string.status_scheduled_maintenance),
                supporting = stringResource(R.string.status_item_count, status.scheduledMaintenances.size),
            )
            status.scheduledMaintenances.forEach { incident ->
                IncidentCard(incident, compact, openUrl)
            }
        }

        SectionHeading(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.status_recent_events),
            supporting = if (status.partialHistory) {
                stringResource(R.string.status_history_partial)
            } else {
                stringResource(R.string.status_history_complete)
            },
        )
        if (status.recentIncidents.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    stringResource(R.string.status_recent_empty),
                    modifier = Modifier.padding(if (compact) 14.dp else 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            status.recentIncidents.forEach { incident ->
                IncidentCard(incident, compact, openUrl)
            }
        }

        TextButton(
            onClick = { openSafeUrl(openUrl, status.pageUrl) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.status_open_official))
        }
}

@Composable
private fun OverallStatusHero(
    status: ServiceStatus,
    accent: Color,
    compact: Boolean,
) {
    val resources = LocalResources.current
    val indicatorLabel = StatusPresentation.indicatorLabel(status.indicator, resources)
    val heroA11y = stringResource(R.string.status_current_a11y, indicatorLabel)
    val heroBrush = Brush.linearGradient(
        listOf(
            accent.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )
    val icon = when (status.indicator) {
        StatusIndicator.Unknown -> Icons.Outlined.CloudOff
        StatusIndicator.None -> Icons.Outlined.CheckCircle
        StatusIndicator.Minor -> Icons.Outlined.WarningAmber
        StatusIndicator.Major, StatusIndicator.Critical -> Icons.Outlined.ErrorOutline
        StatusIndicator.Maintenance -> Icons.Outlined.Build
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = heroA11y
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .background(heroBrush)
                .padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 44.dp else 52.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(if (compact) 24.dp else 28.dp),
                        tint = accent,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.status_availability),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        indicatorLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            val degraded = status.components.count { it.status != ComponentStatus.Operational }
            Text(
                if (degraded == 0) {
                    stringResource(R.string.status_all_components_ok, status.components.size)
                } else {
                    stringResource(
                        R.string.status_degraded_components,
                        degraded,
                        status.components.size - degraded,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusFreshnessRow(status)
        }
    }
}

@Composable
private fun ComponentRow(name: String, status: ComponentStatus) {
    val color = componentColor(status)
    val label = StatusPresentation.componentLabel(status, LocalContext.current.resources)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$name $label" }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncidentCard(
    incident: StatusIncident,
    compact: Boolean,
    openUrl: (String) -> Unit,
) {
    val url = StatusPresentation.incidentUrl(incident.shortlink, incident.id)
    val latest = incident.updates.firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (url != null) {
                    Modifier
                        .semantics { role = Role.Button }
                        .clickable { openSafeUrl(openUrl, url) }
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 14.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    incident.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (url != null) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.status_open_incident),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(label = StatusPresentation.incidentStatusLabel(incident.status, LocalContext.current.resources))
                if (incident.impact.isNotBlank() && incident.impact != "none") {
                    StatusChip(label = StatusPresentation.impactLabel(incident.impact, LocalContext.current.resources))
                }
                StatusPresentation.formatInstant(
                    incident.updatedAt ?: incident.resolvedAt ?: incident.createdAt,
                    LocalDisplayZone.current,
                )
                    ?.let { StatusChip(label = it) }
            }
            if (incident.affectedComponents.isNotEmpty()) {
                Text(
                    incident.affectedComponents.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            latest?.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusFreshnessRow(status: ServiceStatus, showRefreshHint: Boolean = true) {
    val stamp = DisplayTime.formatStoredDateTime(status.fetchedAt, LocalDisplayZone.current, withYear = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (status.fromCache) Icons.Outlined.Cached else Icons.Outlined.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(
                stringResource(R.string.status_last_updated, stamp ?: "—"),
                if (status.fromCache) stringResource(R.string.freshness_cached) else null,
                if (showRefreshHint) stringResource(R.string.freshness_pull_hint) else null,
            ).joinToString(" · "),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun indicatorColor(indicator: StatusIndicator): Color {
    val chart = LocalPulseChartColors.current
    return when (indicator) {
        StatusIndicator.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusIndicator.None -> chart.healthy
        StatusIndicator.Minor -> chart.warning
        StatusIndicator.Major, StatusIndicator.Critical -> chart.critical
        StatusIndicator.Maintenance -> MaterialTheme.colorScheme.tertiary
    }
}

@Composable
private fun componentColor(status: ComponentStatus): Color {
    val chart = LocalPulseChartColors.current
    return when (status) {
        ComponentStatus.Operational -> chart.healthy
        ComponentStatus.DegradedPerformance, ComponentStatus.UnderMaintenance -> chart.warning
        ComponentStatus.PartialOutage, ComponentStatus.MajorOutage, ComponentStatus.Unknown -> chart.critical
    }
}

private fun openSafeUrl(openUrl: (String) -> Unit, url: String) {
    if (StatusPresentation.isSafeStatusUrl(url)) {
        openUrl(url)
    }
}

@Composable
private fun rememberOpenStatusUrl(onOpenFailed: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    return remember(context, resources, toolbarColor, onOpenFailed) {
        { url ->
            if (!CursorCustomTabs.open(context, url, toolbarColor)) {
                onOpenFailed(resources.getString(R.string.tasks_open_page_failed))
            }
        }
    }
}

@Composable
private fun ComponentTile(name: String, status: ComponentStatus, modifier: Modifier) {
    val color = componentColor(status)
    val label = StatusPresentation.componentLabel(status, androidx.compose.ui.platform.LocalResources.current)
    Surface(modifier.semantics { contentDescription = "$name $label" }, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}
