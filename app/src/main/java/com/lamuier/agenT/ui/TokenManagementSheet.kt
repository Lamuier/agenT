package com.lamuier.agenT.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalResources
import com.lamuier.agenT.data.InteractionPreferences
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexCredentialInfo
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.model.TokenFormError
import com.lamuier.agenT.model.TokenFormRules
import com.lamuier.agenT.model.TokenProvider
import com.lamuier.agenT.util.DeviceCredentialGate
import com.lamuier.agenT.util.SensitiveContent
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TokenManagementSheet(
    provider: TokenProvider, cursorAccount: CursorAccount?, codexCredential: CodexCredentialInfo?,
    busy: Boolean, error: String?, onProviderChange: (TokenProvider) -> Unit, onClearError: () -> Unit,
    onDismiss: () -> Unit, onSave: (TokenProvider, String, String, () -> Unit) -> Unit,
    onDeleteRequest: (TokenProvider) -> Unit, onRevealSavedToken: (TokenProvider) -> String,
) {
    val activity = LocalContext.current.tokenActivity()
    val draft = remember(activity) { activity?.let { androidx.lifecycle.ViewModelProvider(it)[TokenDraftViewModel::class.java] } }
    DisposableEffect(draft, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) draft?.clear() }
    }
    val body: @Composable () -> Unit = {
        val enabled by InteractionPreferences.get(LocalContext.current).hapticFeedbackEnabled.collectAsStateWithLifecycle()
        FeedbackProvider(enabled) { TokenManagementContent(provider, cursorAccount, codexCredential, busy, error, onProviderChange,
            onClearError, { draft?.clear(); onDismiss() }, onSave, onDeleteRequest, onRevealSavedToken) }
    }
    if (LocalWindowFolds.current.isNotEmpty()) {
        FoldSafeModal(onDismissRequest = { if (!busy) { draft?.clear(); onDismiss() } },
            secure = true, dismissible = !busy, content = body)
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) { draft?.clear(); onDismiss() } },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            properties = ModalBottomSheetProperties(SecureFlagPolicy.SecureOn, !busy, !busy), content = { body() })
    }

}

/** Also rendered by the Debug fixture activity with empty inputs and inert callbacks. */
@Composable
internal fun TokenManagementContent(
    provider: TokenProvider, cursorAccount: CursorAccount?, codexCredential: CodexCredentialInfo?,
    busy: Boolean, error: String?, onProviderChange: (TokenProvider) -> Unit, onClearError: () -> Unit,
    onDismiss: () -> Unit, onSave: (TokenProvider, String, String, () -> Unit) -> Unit,
    onDeleteRequest: (TokenProvider) -> Unit, onRevealSavedToken: (TokenProvider) -> String,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = LocalAppFeedback.current
    val cursor = provider == TokenProvider.Cursor
    val revision = if (cursor) cursorAccount?.updatedAt else codexCredential?.updatedAt
    val existingMetadata = if (cursor) cursorAccount?.alias else codexCredential?.accountId
    val hasCredential = if (cursor) cursorAccount != null else codexCredential != null
    val activity = context.tokenActivity()
    val draft = remember(activity) { activity?.let { androidx.lifecycle.ViewModelProvider(it)[TokenDraftViewModel::class.java] }
        ?: TokenDraftViewModel() }
    SideEffect { draft.activate(provider, revision, existingMetadata.orEmpty()) }
    var metadata by draft.metadata
    var token by draft.token
    var visible by remember(provider, revision) { mutableStateOf(false) }
    var revealed by remember(provider, revision) { mutableStateOf<String?>(null) }
    var savedVisible by remember(provider, revision) { mutableStateOf(false) }
    var copied by remember(provider, revision) { mutableStateOf(false) }
    var localError by remember(provider, revision) { mutableStateOf<String?>(null) }
    var revealBusy by remember(provider, revision) { mutableStateOf(false) }
    var help by remember(provider) { mutableStateOf(false) }
    var saved by remember(provider) { mutableStateOf(false) }
    val active = remember(provider, revision) { AtomicBoolean(true) }
    DisposableEffect(active) { onDispose { active.set(false) } }
    val blocked = busy || revealBusy

    fun clearFeedback() { localError = null; saved = false; onClearError() }
    fun submit() {
        clearFeedback()
        val failure = TokenFormRules.validate(provider, existingMetadata, metadata, token)
        val resource = when (failure) {
            TokenFormError.AliasRequired -> R.string.account_alias_required
            TokenFormError.AliasTooLong -> R.string.account_alias_too_long
            TokenFormError.TokenRequired -> R.string.token_management_token_required
            TokenFormError.TokenTooShort -> R.string.token_management_token_too_short
            TokenFormError.NoChanges -> R.string.account_no_changes
            null -> null
        }
        if (resource != null) { localError = resources.getString(resource); return }
        onSave(provider, metadata.trim(), token.trim()) {
            token = ""; visible = false; revealed = null; savedVisible = false; copied = false; saved = true
        }
    }
    fun revealSaved() {
        val host = context.tokenActivity()
        if (host == null) { localError = resources.getString(R.string.account_cannot_start_gate); return }
        clearFeedback()
        revealBusy = true
        DeviceCredentialGate.authenticate(
            host, resources.getString(R.string.account_reveal_gate_title),
            resources.getString(R.string.account_reveal_gate_subtitle),
            onSuccess = {
                if (active.get()) {
                    runCatching { onRevealSavedToken(provider) }
                        .onSuccess { revealed = it; savedVisible = false; copied = false }
                        .onFailure { localError = it.message ?: resources.getString(R.string.account_cannot_read_token) }
                    revealBusy = false
                }
            },
            onError = { if (active.get()) { localError = it; revealBusy = false } },
            onCanceled = { if (active.get()) revealBusy = false },
        )
    }

    if (help && cursor) {
        TokenHelpContent(onDismiss = { help = false }, actionLabel = stringResource(R.string.token_management_back))
        return
    }
    HingeSafeLayout {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.token_management_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.token_management_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss, enabled = !blocked) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.symbol_close), stringResource(R.string.action_close))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TokenProvider.entries.forEach { item ->
                FilterChip(selected = provider == item, onClick = {
                    if (provider != item) {
                        feedback.selection()
                        token = ""; revealed = null; savedVisible = false; visible = false; copied = false; localError = null; saved = false
                        onClearError(); onProviderChange(item)
                    }
                }, enabled = !blocked, label = { Text(item.name) }, modifier = Modifier.weight(1f))
            }
        }
        val invalid = if (cursor) cursorAccount?.tokenExpired == true else codexCredential?.let { it.expired || it.invalid } == true
        Text(stringResource(when {
            !hasCredential -> R.string.connection_not_configured
            invalid -> R.string.connection_token_expired
            else -> R.string.token_management_saved
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (saved) Text(stringResource(R.string.token_management_save_success), color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = metadata, onValueChange = { metadata = it; clearFeedback() },
            label = { Text(stringResource(if (cursor) R.string.account_alias_label else R.string.codex_account_id_label)) },
            supportingText = { if (!cursor) Text(stringResource(R.string.codex_account_id_optional)) },
            enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it; clearFeedback() },
            label = { Text(stringResource(R.string.token_management_token_label)) },
            supportingText = { Text(stringResource(when {
                cursor && hasCredential -> R.string.token_management_replace_hint
                !cursor && hasCredential -> R.string.codex_replace_hint
                else -> R.string.token_management_paste_hint
            })) },
            singleLine = true, enabled = !blocked, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = !blocked) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(R.string.codex_toggle_token_visibility))
                }
            },
        )
        TextButton(onClick = { help = !help }, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_token_help_title))
        }
        if (help && !cursor) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.codex_token_help_steps), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.codex_token_help_paths), style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
        if (hasCredential) {
            OutlinedButton(onClick = ::revealSaved, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
                if (revealBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.token_management_reveal))
            }
        }
        revealed?.let { secret ->
            // Device authentication is required before this read-only field can exist.
            OutlinedTextField(value = secret, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text(stringResource(R.string.account_saved_token_title)) },
                visualTransformation = if (savedVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { savedVisible = !savedVisible }) {
                    Icon(if (savedVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(R.string.codex_toggle_token_visibility))
                } }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { SensitiveContent.copyToClipboard(context, secret); copied = true; feedback.success() }) {
                    Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
                }
                TextButton(onClick = { revealed = null; savedVisible = false; copied = false }) {
                    Text(stringResource(R.string.token_management_hide))
                }
            }
        }
        Button(onClick = ::submit, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.token_management_save))
        }
        if (hasCredential) TextButton(onClick = { onDeleteRequest(provider) }, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.token_management_delete), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.token_management_security), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

private fun Context.tokenActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.tokenActivity()
    else -> null
}
