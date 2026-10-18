package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.providers.ProvidersUiState
import com.lamuier.agenT.providers.UsageProvider

/** Configuration depends on saved credentials, never on request success or cached payloads. */
internal fun configuredSourceIds(cursor: Boolean, codex: Boolean, providers: ProvidersUiState): List<String> = buildList {
    if (cursor) add("cursor")
    if (codex) add("codex")
    UsageProvider.entries.filter { providers[it].credential != null }.forEach { add(it.id) }
}

@Composable
internal fun ConfigureSourcesCard(onConfigure: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("configure-sources"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sources_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sources_empty_description), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConfigure) { Text(stringResource(R.string.sources_configure)) }
        }
    }
}
