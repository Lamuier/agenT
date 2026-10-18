package com.lamuier.agenT.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lamuier.agenT.R
import com.lamuier.agenT.usage.UsageHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UsageHistorySettings() {
    val context = LocalContext.current
    val store = remember(context) { UsageHistoryStore(context) }
    val revision by UsageHistoryStore.changes.collectAsState()
    val enabled = remember(revision) { store.enabled() }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    fun change(clearing: Boolean = false, action: () -> Unit) {
        if (busy) return
        busy = true; error = false; cleared = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            error = result.isFailure; cleared = clearing && result.isSuccess; busy = false
        }
    }
    SettingToggleRow(stringResource(R.string.u_collect_history), stringResource(R.string.u_collect_description), enabled,
        onCheckedChange = { value -> change { store.setEnabled(value) } })
    TextButton(onClick = { confirming = true }, enabled = !busy) { Text(stringResource(R.string.u_clear_history)) }
    if (error) Text(stringResource(R.string.u_clear_error), color = MaterialTheme.colorScheme.error)
    if (cleared) Text(stringResource(R.string.u_cleared))
    if (confirming) FoldSafeAlertDialog(onDismissRequest = { confirming = false }, title = { Text(stringResource(R.string.u_clear_history)) },
        text = { Text(stringResource(R.string.u_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirming = false; change(clearing = true) { store.clear() } }) { Text(stringResource(R.string.u_clear_history)) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } })
}
