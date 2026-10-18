package com.lamuier.agenT.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.util.AntigravityLinks

@Composable
internal fun AntigravityTasksContent(onOpenFailed: (String) -> Unit) {
    val openUrl = rememberOpenCursorUrl(onOpenFailed)
    val tasks: @Composable (Boolean) -> Unit = { compact ->
        SectionHeading(icon = Icons.Outlined.SmartToy, title = stringResource(R.string.antigravity_tasks_title),
            supporting = stringResource(R.string.antigravity_tasks_supporting))
        AntigravityRemoteCard(openUrl, compact)
    }
    AdaptivePaneLayout(primaryFraction = 0.4f, maxContentWidthDp = 1440f,
        primary = { AdaptiveTabContent { compact -> tasks(compact) } },
        secondary = { AdaptiveTabContent { AntigravitySetupCard(openUrl) } },
        single = { AdaptiveTabContent { compact -> tasks(compact); AntigravitySetupCard(openUrl) } },
    )
}

@Composable
private fun AntigravityRemoteCard(openUrl: (String) -> Unit, compact: Boolean) {
    TaskCardContainer(compact = compact, modifier = Modifier.testTag("antigravity-open-tasks")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.antigravity_open_tasks)) {
            openUrl(AntigravityLinks.REMOTE_CONTROL)
        }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.antigravity_open_tasks), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TaskChip(stringResource(R.string.antigravity_web_label), MaterialTheme.colorScheme.tertiary)
        Text(stringResource(R.string.antigravity_tasks_description), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.antigravity_tasks_boundary), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AntigravitySetupCard(openUrl: (String) -> Unit) {
    AntigravityCard(R.string.antigravity_setup_title) {
        Text(stringResource(R.string.antigravity_setup_desktop))
        Text(stringResource(R.string.antigravity_setup_cli))
        Text(stringResource(R.string.antigravity_setup_account))
        TextButton(onClick = { openUrl(AntigravityLinks.REMOTE_CONTROL_HELP) }) {
            Text(stringResource(R.string.antigravity_open_guide))
        }
    }
}

@Composable
internal fun AntigravityStatusContent(openUrl: (String) -> Unit) {
    AntigravityCard(R.string.antigravity_service_name, Modifier.testTag("antigravity-status")) {
        Text(stringResource(R.string.antigravity_status_unknown), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.antigravity_status_description))
        TextButton(onClick = { openUrl(AntigravityLinks.SUPPORT) }, modifier = Modifier.testTag("antigravity-open-support")) {
            Text(stringResource(R.string.antigravity_open_support))
        }
    }
}

@Composable
internal fun AntigravityUsageContent(onOpenFailed: (String) -> Unit) {
    val openUrl = rememberOpenCursorUrl(onOpenFailed)
    AntigravityCard(R.string.antigravity_usage_title, Modifier.testTag("antigravity-usage")) {
        Text(stringResource(R.string.antigravity_usage_description))
        Text(stringResource(R.string.antigravity_usage_limits), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { openUrl(AntigravityLinks.USAGE_HELP) }) {
            Text(stringResource(R.string.antigravity_open_usage_help))
        }
    }
}

@Composable
private fun AntigravityCard(title: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
