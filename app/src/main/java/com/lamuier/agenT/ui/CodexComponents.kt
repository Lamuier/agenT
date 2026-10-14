package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaWindow
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.util.AssistantPresentation
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
internal fun CodexQuotaWindowRow(title: String, window: CodexQuotaWindow) {
    val used = window.usedPercent.takeIf { it.isFinite() && it >= 0 }
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
        LinearProgressIndicator(progress = { ((used?.coerceIn(0.0, 100.0) ?: 0.0) / 100.0).toFloat() },
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
