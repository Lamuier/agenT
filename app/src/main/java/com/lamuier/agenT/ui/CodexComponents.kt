package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.codex.CodexQuotaWindow
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.AssistantPresentation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CodexQuotaSection(
    state: CodexQuotaUiState,
    onManageCredential: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionHeading(
        icon = Icons.Outlined.AutoAwesome,
        title = stringResource(R.string.agent_service_codex_title),
        supporting = stringResource(R.string.codex_section_supporting),
    )
    when {
        state.booting -> CodexMessageCard(
            icon = Icons.Outlined.Sync,
            title = stringResource(R.string.codex_status_loading),
            description = stringResource(R.string.codex_status_loading_body),
            loading = true,
        )
        state.credential == null -> CodexMessageCard(
            icon = Icons.Outlined.KeyOff,
            title = stringResource(R.string.codex_empty_title),
            description = stringResource(R.string.codex_empty_description),
            actionLabel = stringResource(R.string.action_settings),
            onAction = onManageCredential,
        )
        else -> {
            if (state.credential.invalid || state.credential.expired) {
                CodexMessageCard(
                    icon = Icons.Outlined.KeyOff,
                    title = stringResource(R.string.codex_status_token_problem),
                    description = stringResource(R.string.codex_token_needs_update),
                    actionLabel = stringResource(R.string.action_settings),
                    onAction = onManageCredential,
                    error = true,
                )
            }
            state.usage?.let { CodexUsageSummary(it) }
            if (state.usage == null && state.refreshing) {
                CodexMessageCard(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.codex_status_refreshing),
                    description = stringResource(R.string.codex_status_loading_body),
                    loading = true,
                )
            }
            if (
                state.usage == null &&
                !state.refreshing &&
                !state.credential.invalid &&
                !state.credential.expired
            ) {
                CodexMessageCard(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.codex_no_usage),
                    description = state.error ?: stringResource(R.string.network_retry_hint),
                    actionLabel = stringResource(R.string.codex_refresh),
                    onAction = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun CodexMessageCard(
    icon: ImageVector,
    title: String,
    description: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    error: Boolean = false,
) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) content else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodexUsageSummary(usage: CodexQuotaOverview) {
    val updatedLabel = stringResource(R.string.codex_updated_at, usage.fetchedAt)
    val cacheLabel = if (usage.fromCache) {
        stringResource(R.string.codex_cached_age, usage.cacheAgeSeconds)
    } else {
        null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.codex_usage_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        usage.planType?.uppercase(Locale.getDefault())
                            ?: stringResource(R.string.codex_plan_unknown),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                usage.resetCreditsAvailable?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text(
                            stringResource(R.string.codex_reset_credits, it),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.size(18.dp))
            usage.primary?.let {
                CodexQuotaWindowRow(stringResource(R.string.codex_window_five_hour), it)
            }
            usage.secondary?.let {
                HorizontalDivider(Modifier.padding(vertical = 17.dp))
                CodexQuotaWindowRow(stringResource(R.string.codex_window_weekly), it)
            }
            usage.additional.forEachIndexed { index, window ->
                HorizontalDivider(Modifier.padding(vertical = 17.dp))
                CodexQuotaWindowRow(stringResource(R.string.codex_window_additional, index + 1), window)
            }
            HorizontalDivider(Modifier.padding(top = 18.dp, bottom = 12.dp))
            Text(
                text = listOfNotNull(updatedLabel, cacheLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CodexQuotaWindowRow(title: String, window: CodexQuotaWindow) {
    val used = window.usedPercent.coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(codexResetLabel(window), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(AssistantPresentation.percent(used), fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, letterSpacing = (-0.5).sp)
        }
        LinearProgressIndicator(progress = { (used / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
internal fun codexResetLabel(window: CodexQuotaWindow): String {
    window.resetAtEpochSeconds?.let { resetAt ->
        val formatted = AssistantPresentation.resetTime(resetAt, LocalDisplayZone.current)
        return stringResource(R.string.codex_resets_at, formatted)
    }
    val seconds = window.resetAfterSeconds ?: return stringResource(R.string.codex_reset_unknown)
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> stringResource(R.string.codex_resets_in_days_hours, days, hours)
        hours > 0 -> stringResource(R.string.codex_resets_in_hours_minutes, hours, minutes)
        else -> stringResource(R.string.codex_resets_in_minutes, minutes.coerceAtLeast(1))
    }
}
