package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** A full window makes Jetpack hinge coordinates valid for the dialog as well. */
@Composable
internal fun FoldSafeModal(onDismissRequest: () -> Unit, secure: Boolean = false,
    dismissible: Boolean = true, content: @Composable () -> Unit) {
    Dialog(onDismissRequest, DialogProperties(dismissOnBackPress = dismissible,
        dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)) {
        HingeSafeLayout {
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) { content() }
                }
        }
    }
}

@Composable
internal fun FoldSafeAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null) {
    if (LocalWindowFolds.current.isEmpty()) {
        AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
            dismissButton = dismissButton, title = title, text = text)
    } else FoldSafeModal(onDismissRequest) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            title?.let { ProvideTextStyle(MaterialTheme.typography.headlineSmall, it) }
            text?.let { ProvideTextStyle(MaterialTheme.typography.bodyMedium, it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton?.invoke(); confirmButton() }
        }
    }
}
