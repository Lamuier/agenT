package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaViewModel
import com.lamuier.agenT.data.NotificationSettings
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.AppStage
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.ShortcutAction
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.DisplayTimeZones
import kotlinx.coroutines.delay

private const val FOREGROUND_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L

@Composable
fun AgenTApp(
    viewModel: AgenTViewModel,
    codexViewModel: CodexQuotaViewModel,
    themeSettings: ThemeSettings,
    notificationSettings: NotificationSettings,
    pendingShortcutAction: ShortcutAction?,
    onShortcutActionConsumed: () -> Unit,
    pendingOpenTab: DashboardTab? = null,
    onOpenTabConsumed: () -> Unit = {},
    hapticFeedbackEnabled: Boolean = true,
    onHapticFeedbackChange: (Boolean) -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit,
    onPaletteChange: (ColorPalette) -> Unit,
    timeZoneId: String,
    onTimeZoneChange: (String) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onLiveUpdatesToggle: (Boolean) -> Unit,
    onThresholdRemindersToggle: (Boolean) -> Unit,
    percentDisplayMode: PercentDisplayMode,
    onPercentDisplayModeChange: (PercentDisplayMode) -> Unit,
    tabOrder: List<DashboardTab>,
    onTabOrderChange: (List<DashboardTab>) -> Unit,
    onTabOrderReset: () -> Unit,
    onCodexResetRemindersToggle: (Boolean) -> Unit = {},
    onCatLanguageToggle: (Boolean) -> Unit = {},
    codexUsageAutoRefresh: Boolean = true,
    onCodexUsageAutoRefreshChange: (Boolean) -> Unit = {},
    onInitialContentReady: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val codexState by codexViewModel.state.collectAsStateWithLifecycle()
    if (state.stage == AppStage.Dashboard && !codexState.booting) {
        // 成功组合出本地恢复后的首页，才放行首帧；后续网络刷新不再拦截绘制。
        SideEffect(onInitialContentReady)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val account = remember(state.accounts, state.selectedAccountId) {
        state.accounts.firstOrNull { it.id == state.selectedAccountId }
            ?: state.accounts.firstOrNull()
    }

    var manageTokens by rememberSaveable { mutableStateOf(false) }
    var tokenProvider by rememberSaveable { mutableStateOf(com.lamuier.agenT.model.TokenProvider.Cursor) }
    var showTokenHelp by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var returnToSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    val dashboardStates = rememberSaveableStateHolder()
    var deleteTarget by remember { mutableStateOf<CursorAccount?>(null) }
    var showCodexDeleteDialog by remember { mutableStateOf(false) }
    val sheetOpen = manageTokens || showTokenHelp || showSettings
    val displayZone = remember(timeZoneId) { DisplayTimeZones.resolve(timeZoneId) }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(codexState.credential, codexState.usage, codexState.serviceStatus, codexState.statusError,
        state.serviceStatus, state.statusError) {
        com.lamuier.agenT.widget.AgenTWidgetUpdater.requestUpdate(appContext)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(appContext).refreshFromCache()
        }
    }
    fun openTokenManagement(fromSettings: Boolean = false, preferred: com.lamuier.agenT.model.TokenProvider? = null) {
        if (!manageTokens) tokenProvider = com.lamuier.agenT.model.TokenProvider.initial(account != null, codexState.credential != null, preferred)
        viewModel.clearError()
        codexViewModel.clearError()
        showSettings = false
        showTokenHelp = false
        returnToSettings = fromSettings
        manageTokens = true
    }

    LaunchedEffect(pendingShortcutAction, state.stage, state.loadingAccounts, codexState.booting,
        state.submitting, codexState.saving) {
        val action = pendingShortcutAction ?: return@LaunchedEffect
        if (state.stage != AppStage.Dashboard || state.loadingAccounts || codexState.booting ||
            state.submitting || codexState.saving) return@LaunchedEffect
        onShortcutActionConsumed()
        deleteTarget = null
        showCodexDeleteDialog = false
        when (action) {
            ShortcutAction.Overview, ShortcutAction.Tasks -> Unit // Consumed as a tab destination by MainActivity.
            ShortcutAction.ManageTokens -> openTokenManagement()
            ShortcutAction.Settings -> {
                manageTokens = false
                showTokenHelp = false
                returnToSettings = false
                showSettings = true
            }
        }
    }

    LaunchedEffect(state.stage) {
        if (state.stage != AppStage.Dashboard) {
            manageTokens = false
            showTokenHelp = false
            showSettings = false
            deleteTarget = null
        }
    }

    LaunchedEffect(state.error, sheetOpen) {
        if (sheetOpen) return@LaunchedEffect
        val message = state.error ?: return@LaunchedEffect
        viewModel.clearError()
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(codexState.error, sheetOpen) {
        if (sheetOpen) return@LaunchedEffect
        val message = codexState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(state.stage, state.loadingAccounts, account?.id, state.selectedAccountId) {
        if (
            state.stage == AppStage.Dashboard &&
            !state.loadingAccounts &&
            account != null &&
            state.selectedAccountId != account.id
        ) {
            viewModel.selectAccount(account.id)
        }
    }

    LaunchedEffect(
        lifecycleOwner,
        state.stage,
        account?.id,
        account?.tokenExpired,
        state.submitting,
        sheetOpen,
    ) {
        if (
            state.stage != AppStage.Dashboard ||
            account == null ||
            account.tokenExpired ||
            state.submitting ||
            sheetOpen
        ) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshSelected(force = false, silent = true, includeStatus = false)
            while (true) {
                delay(FOREGROUND_REFRESH_INTERVAL_MS)
                viewModel.refreshSelected(force = false, silent = true, includeStatus = false)
            }
        }
    }

    LaunchedEffect(lifecycleOwner, state.stage, sheetOpen) {
        if (state.stage != AppStage.Dashboard || sheetOpen) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshStatus(force = false, silent = true)
            codexViewModel.refreshStatus(force = false)
            while (true) {
                delay(FOREGROUND_REFRESH_INTERVAL_MS)
                viewModel.refreshStatus(force = false, silent = true)
                codexViewModel.refreshStatus(force = false)
            }
        }
    }

    LaunchedEffect(
        lifecycleOwner,
        state.stage,
        codexState.credential?.updatedAt,
        codexState.credential?.expired,
        codexState.credential?.invalid,
        codexState.saving,
        sheetOpen,
    ) {
        val credential = codexState.credential
        if (
            state.stage != AppStage.Dashboard ||
            credential == null ||
            credential.expired ||
            credential.invalid ||
            codexState.saving ||
            sheetOpen
        ) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            codexViewModel.refresh(force = false)
            while (true) {
                delay(codexViewModel.nextRefreshDelayMillis())
                codexViewModel.refresh(force = false)
            }
        }
    }

    CompositionLocalProvider(LocalDisplayZone provides displayZone) {
    when (state.stage) {
        AppStage.Booting -> Unit
        AppStage.Dashboard -> if (!showSettings) dashboardStates.SaveableStateProvider("dashboard") { DashboardScreen(
            state = state,
            codexState = codexState,
            snackbarHostState = snackbarHostState,
            tabOrder = tabOrder,
            pendingOpenTab = pendingOpenTab,
            onOpenTabConsumed = onOpenTabConsumed,
            onLoadHistoryWindow = viewModel::loadHistoryWindow,
            onRefresh = {
                val cursorConnected = account != null
                val codexConnected = codexState.credential != null
                val publicLoaded = codexState.publicResets != null
                val parts = buildSet {
                    add("cursor-status"); add("codex-status")
                    if (cursorConnected) { add("cursor-usage"); add("cursor-tasks") }
                    if (codexConnected) add("codex-usage")
                    if (publicLoaded) add("public-resets")
                }
                val batch = OperationFeedback.batch(parts)
                if (cursorConnected) {
                    viewModel.refreshUsage(true, true) { batch.finish("cursor-usage", it) }
                    viewModel.refreshTasks(true, true) { batch.finish("cursor-tasks", it) }
                }
                viewModel.refreshStatus(true, true) { batch.finish("cursor-status", it) }
                codexViewModel.refreshStatus(true) { batch.finish("codex-status", it) }
                if (codexConnected) codexViewModel.refresh(true, { batch.finish("codex-usage", it) }, includePublicResets = false)
                if (publicLoaded) codexViewModel.refreshPublicResets(true) { batch.finish("public-resets", it) }
            },
            onRefreshCodex = { codexViewModel.refresh(true, { if (it) OperationFeedback.success() }, includePublicResets = false) },
            onManageCodex = { openTokenManagement(preferred = com.lamuier.agenT.model.TokenProvider.Codex) },
            onManageAccount = { openTokenManagement(preferred = com.lamuier.agenT.model.TokenProvider.Cursor) },
            onShowSettings = { showSettings = true },
            onShowTokenHelp = { showTokenHelp = true },
            onSubscriptionDay = { day -> codexViewModel.setSubscriptionDay(day, OperationFeedback::success) },
            onRefreshPublicResets = { force -> codexViewModel.refreshPublicResets(force, if (force) ({ success: Boolean -> if (success) OperationFeedback.success() }) else null) },
        ) }
    }

    if (manageTokens && state.stage == AppStage.Dashboard) {
        TokenManagementSheet(
            provider = tokenProvider, cursorAccount = account, codexCredential = codexState.credential,
            busy = state.submitting || state.loadingAccounts || codexState.saving || codexState.booting || codexState.resetSaving,
            error = if (tokenProvider == com.lamuier.agenT.model.TokenProvider.Cursor) state.error else codexState.error,
            onProviderChange = { selected ->
                tokenProvider = selected
                viewModel.clearError()
                codexViewModel.clearError()
            },
            onClearError = {
                if (tokenProvider == com.lamuier.agenT.model.TokenProvider.Cursor) viewModel.clearError()
                else codexViewModel.clearError()
            },
            onDismiss = {
                manageTokens = false
                if (returnToSettings) { showSettings = true; returnToSettings = false }
            },
            onSave = { provider, metadata, token, done ->
                val confirmed = { OperationFeedback.success(); done() }
                if (provider == com.lamuier.agenT.model.TokenProvider.Cursor) {
                    val current = account
                    if (current == null) viewModel.addAccount(metadata, token, confirmed)
                    else viewModel.updateAccount(current.id, metadata.takeIf { it != current.alias }, token.ifBlank { null }, confirmed)
                } else codexViewModel.saveCredential(token, metadata, confirmed)
            },
            onDeleteRequest = { provider ->
                if (provider == com.lamuier.agenT.model.TokenProvider.Cursor) deleteTarget = account
                else showCodexDeleteDialog = true
            },
            onRevealSavedToken = { provider ->
                if (provider == com.lamuier.agenT.model.TokenProvider.Cursor)
                    viewModel.revealAccessToken(requireNotNull(account).id)
                else codexViewModel.revealToken()
            },
        )
    }

    if (showTokenHelp) {
        TokenHelpSheet(onDismiss = { showTokenHelp = false })
    }

    if (showSettings) {
        SettingsSheet(
            sectionId = settingsSection,
            onSectionChange = { settingsSection = it },
            hapticFeedbackEnabled = hapticFeedbackEnabled,
            onHapticFeedbackChange = onHapticFeedbackChange,
            settings = themeSettings,
            notificationSettings = notificationSettings,
            onDismiss = { showSettings = false },
            onThemeModeChange = onThemeModeChange,
            onPaletteChange = onPaletteChange,
            timeZoneId = timeZoneId,
            onTimeZoneChange = onTimeZoneChange,
            language = language,
            onLanguageChange = onLanguageChange,
            onLiveUpdatesToggle = onLiveUpdatesToggle,
            onThresholdRemindersToggle = onThresholdRemindersToggle,
            percentDisplayMode = percentDisplayMode,
            onPercentDisplayModeChange = onPercentDisplayModeChange,
            tabOrder = tabOrder,
            onTabOrderChange = onTabOrderChange,
            onTabOrderReset = onTabOrderReset,
            onManageTokens = { openTokenManagement(fromSettings = true) },
            cursorAccount = account,
            codexState = codexState,
            onCodexResetRemindersToggle = onCodexResetRemindersToggle,
            onCatLanguageToggle = onCatLanguageToggle,
            codexUsageAutoRefresh = codexUsageAutoRefresh,
            onCodexUsageAutoRefreshChange = onCodexUsageAutoRefreshChange,
        )
    }


    if (showCodexDeleteDialog) {
        FoldSafeAlertDialog(
            onDismissRequest = { if (!codexState.saving) showCodexDeleteDialog = false },
            title = { Text(stringResource(R.string.codex_delete_token_title)) },
            text = { Text(stringResource(R.string.codex_delete_token_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        codexViewModel.clearCredential {
                            OperationFeedback.success()
                            showCodexDeleteDialog = false
                        }
                    },
                    enabled = !codexState.saving,
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCodexDeleteDialog = false },
                    enabled = !codexState.saving,
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleteTarget?.let { target ->
        FoldSafeAlertDialog(
            onDismissRequest = { if (!state.submitting) deleteTarget = null },
            title = {
                Text(
                    stringResource(R.string.account_delete_title, target.alias),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(stringResource(R.string.account_delete_body))
            },
            confirmButton = {
                Button(
                    enabled = !state.submitting,
                    onClick = {
                        viewModel.deleteAccount(target.id) {
                            OperationFeedback.success()
                            deleteTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !state.submitting, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    }
}
