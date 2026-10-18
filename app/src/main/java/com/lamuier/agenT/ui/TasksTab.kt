package com.lamuier.agenT.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lamuier.agenT.R
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.model.AgentTask
import com.lamuier.agenT.model.AgentTaskPrStatus
import com.lamuier.agenT.model.AgentTaskSource
import com.lamuier.agenT.model.AgentTaskStatus
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.model.TaskGroupMode
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.LocalPulseChartColors
import com.lamuier.agenT.util.AgentTaskGrouping
import com.lamuier.agenT.util.AgentTaskPresentation
import com.lamuier.agenT.util.CursorCustomTabs
import com.lamuier.agenT.util.DisplayTime
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskProviderSelector(antigravity: Boolean, onSelect: (Boolean) -> Unit) {
    val feedback = LocalAppFeedback.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !antigravity, onClick = { if (antigravity) { onSelect(false); feedback.selection() } }, label = { Text("Cursor") }, modifier = Modifier.testTag("tasks-provider-cursor"))
        FilterChip(selected = antigravity, onClick = { if (!antigravity) { onSelect(true); feedback.selection() } },
            label = { Text(stringResource(R.string.antigravity_service_name)) },
            modifier = Modifier.testTag("tasks-provider-antigravity"))
    }
}

@Composable
internal fun TasksTab(
    tasks: AgenTasks?,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenFailed: (String) -> Unit,
) {
    val openUrl = rememberOpenCursorUrl(onOpenFailed)
    when {
        tasks == null && (loading || refreshing) -> DashboardState(
            icon = null,
            title = stringResource(R.string.tasks_loading_title),
            description = stringResource(R.string.tasks_loading_body),
            loading = true,
        )
        tasks == null -> DashboardState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.tasks_unavailable_title),
            description = stringResource(R.string.network_retry_hint),
            primaryActionLabel = stringResource(R.string.action_reload),
            onPrimaryAction = onRetry,
        )
        else -> TasksContent(
            tasks = tasks.copy(fromCache = tasks.fromCache || error != null),
            openUrl = openUrl,
            onOpenFailed = onOpenFailed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksContent(
    tasks: AgenTasks,
    openUrl: (String) -> Unit,
    onOpenFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { DashboardPreferences.get(context) }
    val groupMode by preferences.taskGroupMode.collectAsStateWithLifecycle()
    val zone = LocalDisplayZone.current
    val resources = LocalResources.current
    val visibleTasks = remember(tasks.tasks) { AgentTaskGrouping.visibleTasks(tasks.tasks) }
    val hiddenMergedCount = tasks.tasks.size - visibleTasks.size
    val activeCount = visibleTasks.count {
        it.status == AgentTaskStatus.Running || it.status == AgentTaskStatus.Creating
    }
    val grokBotCount = visibleTasks.count { it.source == AgentTaskSource.GrokBot }
    val groups = remember(visibleTasks, groupMode, zone, resources) {
        AgentTaskGrouping.groups(visibleTasks, groupMode, zone = zone, resources = resources)
    }
    val feedback = LocalAppFeedback.current
    var collapsed by rememberSaveable(groupMode, tasks.accountId, stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
    var selectedId by rememberSaveable(tasks.accountId) { mutableStateOf<String?>(null) }
    val selected = visibleTasks.firstOrNull { it.id == selectedId } ?: visibleTasks.firstOrNull()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val detailScroll = androidx.compose.foundation.rememberScrollState()
    val list: @Composable (Boolean) -> Unit = { paired ->
    AdaptiveLazyTabContent(state = listState) { compact ->
        item(key = "summary") {
        SectionHeading(
            icon = Icons.Outlined.SmartToy,
            title = stringResource(R.string.tasks_heading),
            supporting = when {
                tasks.tasks.isEmpty() -> stringResource(R.string.tasks_empty_records)
                visibleTasks.isEmpty() -> stringResource(R.string.tasks_hidden_merged_only, hiddenMergedCount)
                else -> stringResource(R.string.tasks_summary, visibleTasks.size, activeCount) +
                    (if (grokBotCount > 0) stringResource(R.string.tasks_summary_grok, grokBotCount) else "") +
                    (if (hiddenMergedCount > 0) stringResource(R.string.tasks_summary_hidden, hiddenMergedCount) else "")
            },
        )
        if (visibleTasks.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    if (hiddenMergedCount > 0) {
                        stringResource(R.string.tasks_empty_all_merged)
                    } else {
                        stringResource(R.string.tasks_empty_none)
                    },
                    modifier = Modifier.padding(if (compact) 14.dp else 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskGroupMode.entries.forEachIndexed { index, mode ->
                    FilterChip(selected = groupMode == mode,
                        onClick = { if (groupMode != mode) { preferences.setTaskGroupMode(mode); feedback.selection() } },
                        label = { Text(stringResource(mode.labelRes)) })
                }
            }
        }
        } // summary
        groups.forEach { group ->
            val expanded = group.key !in collapsed
            item(key = "group:${group.key}") {
                TaskGroupHeader(title = group.title, count = group.tasks.size,
                    activeCount = group.tasks.count { it.status == AgentTaskStatus.Running || it.status == AgentTaskStatus.Creating },
                    expanded = expanded, onToggle = {
                        feedback.selection()
                        collapsed = if (expanded) collapsed + group.key else collapsed - group.key
                    })
            }
            if (expanded) items(group.tasks, key = { "task:${it.id}" }) { task ->
                TaskCard(task, compact, openUrl, onOpenFailed,
                    onSelect = if (paired) ({ selectedId = task.id; feedback.selection() }) else null,
                    selected = paired && selected?.id == task.id)
            }
        }
    }
    }
    AdaptivePaneLayout(allowPair = visibleTasks.isNotEmpty(), primaryFraction = 0.4f, maxContentWidthDp = 1440f,
        single = { list(false) }, primary = { list(true) }, secondary = {
            AdaptiveTabContent(scrollState = detailScroll) { compact ->
                Text(stringResource(R.string.tasks_detail_title), style = MaterialTheme.typography.headlineSmall)
                selected?.let { task ->
                    TaskDetails(task, openUrl, onOpenFailed)
                }
            }
        })
}

@Composable
private fun TaskGroupHeader(
    title: String,
    count: Int,
    activeCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val groupCount = stringResource(R.string.tasks_group_count, count)
    val supporting = if (activeCount > 0) {
        groupCount + stringResource(R.string.tasks_group_active, activeCount)
    } else {
        groupCount
    }
    val description = stringResource(
        if (expanded) R.string.tasks_collapse_group else R.string.tasks_expand_group,
        title,
        supporting,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                heading()
                contentDescription = description
            }
            .clickable(onClick = onToggle)
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            supporting,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: AgentTask,
    compact: Boolean,
    openUrl: (String) -> Unit,
    onOpenFailed: (String) -> Unit,
    onSelect: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val resources = LocalResources.current
    val displayName = task.name.ifBlank { stringResource(R.string.task_untitled) }
    val statusLabel = AgentTaskPresentation.statusLabel(task.status, resources)
    val conversationA11y = stringResource(if (onSelect == null) R.string.tasks_open_conversation_a11y
        else R.string.tasks_show_detail_a11y, displayName, statusLabel)
    val prUrl = task.prUrl?.takeIf(AgentTaskPresentation::isSafeAgentUrl)
    val statusColor = taskStatusColor(task.status)
    TaskCardContainer(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = conversationA11y
                this.selected = selected
            }
            .clickable {
                if (onSelect != null) onSelect() else {
                val url = AgentTaskPresentation.agentConversationUrl(task.id)
                if (url == null) {
                    onOpenFailed(resources.getString(R.string.tasks_invalid_id))
                } else {
                    openUrl(url)
                }
                }
            },
        compact = compact,
        selected = selected,
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TaskChip(
                    label = statusLabel,
                    color = statusColor,
                )
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val location = listOfNotNull(
                AgentTaskPresentation.repoDisplayName(task.repoUrl),
                task.branchName?.trim()?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (location.isNotBlank()) {
                Text(
                    location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (task.source != AgentTaskSource.Unknown) {
                    TaskChip(
                        label = AgentTaskPresentation.sourceLabel(task.source, resources),
                        color = if (task.source == AgentTaskSource.GrokBot) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                task.prStatus?.let { prStatus ->
                    TaskChip(
                        label = AgentTaskPresentation.prStatusLabel(prStatus, resources),
                        color = prStatusColor(prStatus),
                    )
                }
                if (task.linesAdded > 0 || task.linesRemoved > 0) {
                    TaskChip(
                        label = String.format(
                            Locale.US,
                            "+%,d / -%,d",
                            task.linesAdded,
                            task.linesRemoved,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.filesChanged > 0) {
                    TaskChip(
                        label = stringResource(R.string.tasks_files_changed, task.filesChanged),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AgentTaskPresentation.displayModel(task.modelName, task.maxMode)?.let { model ->
                    TaskChip(label = model, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val activity = AgentTaskPresentation.formatRelative(
                    task.latestTimeMs,
                    zone = LocalDisplayZone.current,
                    resources = resources,
                )
                activity?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prUrl != null) {
                    val openPrDescription = stringResource(R.string.tasks_open_pr)
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .semantics {
                                role = Role.Button
                                contentDescription = openPrDescription
                            }
                            .clickable { openUrl(prUrl) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.tasks_view_pr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
    }
}

/** Shared task surface for native tasks and the official Antigravity entry. */
@Composable
internal fun TaskCardContainer(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp) {
        Column(Modifier.padding(if (compact) 14.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun TaskChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TasksFreshnessRow(tasks: AgenTasks) {
    val stamp = DisplayTime.formatStoredClock(tasks.fetchedAt, LocalDisplayZone.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (tasks.fromCache) Icons.Outlined.Cached else Icons.Outlined.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(
                stringResource(if (tasks.fromCache) R.string.freshness_cached else R.string.freshness_updated),
                stamp,
                stringResource(R.string.freshness_pull_hint),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun taskStatusColor(status: AgentTaskStatus): Color {
    val chart = LocalPulseChartColors.current
    return when (status) {
        AgentTaskStatus.Running -> MaterialTheme.colorScheme.primary
        AgentTaskStatus.Creating -> MaterialTheme.colorScheme.tertiary
        AgentTaskStatus.Finished -> chart.healthy
        AgentTaskStatus.Error -> chart.critical
        AgentTaskStatus.Expired, AgentTaskStatus.Unknown ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun prStatusColor(status: AgentTaskPrStatus): Color {
    val chart = LocalPulseChartColors.current
    return when (status) {
        AgentTaskPrStatus.Merged -> MaterialTheme.colorScheme.primary
        AgentTaskPrStatus.Open -> chart.healthy
        AgentTaskPrStatus.Draft -> chart.warning
        AgentTaskPrStatus.Closed, AgentTaskPrStatus.Unknown ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun rememberOpenCursorUrl(onOpenFailed: (String) -> Unit): (String) -> Unit {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetails(task: AgentTask, openUrl: (String) -> Unit, onOpenFailed: (String) -> Unit) {
    val resources = LocalResources.current
    val zone = LocalDisplayZone.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskChip(AgentTaskPresentation.statusLabel(task.status, resources), taskStatusColor(task.status))
                if (task.source != AgentTaskSource.Unknown) TaskChip(AgentTaskPresentation.sourceLabel(task.source, resources), MaterialTheme.colorScheme.tertiary)
                task.prStatus?.let { TaskChip(AgentTaskPresentation.prStatusLabel(it, resources), prStatusColor(it)) }
            }
            Text(task.name.ifBlank { stringResource(R.string.task_untitled) }, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            val fields = listOf(
                R.string.tasks_field_repository to AgentTaskPresentation.repoDisplayName(task.repoUrl),
                R.string.tasks_field_branch to task.branchName,
                R.string.tasks_field_model to AgentTaskPresentation.displayModel(task.modelName, task.maxMode),
                R.string.tasks_field_changes to stringResource(R.string.tasks_detail_changes, task.filesChanged, task.linesAdded, task.linesRemoved),
                R.string.tasks_field_created to DisplayTime.formatEpoch(task.createdAtMs, zone, withYear = true),
                R.string.tasks_field_updated to DisplayTime.formatEpoch(task.latestTimeMs, zone, withYear = true),
            )
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= (480 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    fields.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            row.forEach { (label, value) ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = {
                    AgentTaskPresentation.agentConversationUrl(task.id)?.let(openUrl)
                        ?: onOpenFailed(resources.getString(R.string.tasks_invalid_id))
                }) { Text(stringResource(R.string.tasks_view_conversation)) }
                task.prUrl?.takeIf(AgentTaskPresentation::isSafeAgentUrl)?.let { url ->
                    TextButton(onClick = { openUrl(url) }) { Text(stringResource(R.string.tasks_view_pr)) }
                }
            }
        }
    }
}
