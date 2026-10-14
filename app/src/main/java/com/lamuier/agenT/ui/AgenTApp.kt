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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import com.lamuier.agenT.model.RefreshResult
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.DisplayTimeZones
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation

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
    firstFrameDrawn: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val codexState by codexViewModel.state.collectAsStateWithLifecycle()
    if (state.stage == AppStage.Dashboard && !codexState.booting && pendingShortcutAction == null) {
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
    var visibleTab by remember { mutableStateOf(pendingOpenTab ?: tabOrder.firstOrNull() ?: DashboardTab.Overview) }
    LaunchedEffect(firstFrameDrawn, visibleTab, sheetOpen, lifecycleOwner) {
        val active = firstFrameDrawn && visibleTab == DashboardTab.Usage && !sheetOpen
        if (!active) { viewModel.setHistoryActive(false); codexViewModel.setHistoryActive(false); return@LaunchedEffect }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.setHistoryActive(true); codexViewModel.setHistoryActive(true)
            try { awaitCancellation() }
            finally { viewModel.setHistoryActive(false); codexViewModel.setHistoryActive(false) }
        }
    }
    DisposableEffect(viewModel, codexViewModel) {
        onDispose { viewModel.setHistoryActive(false); codexViewModel.setHistoryActive(false) }
    }
    LaunchedEffect(firstFrameDrawn) {
        if (firstFrameDrawn) codexViewModel.refreshPublicResets(false)
    }
    val displayZone = remember(timeZoneId) { DisplayTimeZones.resolve(timeZoneId) }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(firstFrameDrawn, codexState.credential, codexState.usage, codexState.serviceStatus, codexState.statusError,
        state.serviceStatus, state.statusError) {
        if (!firstFrameDrawn) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.lamuier.agenT.widget.AgenTWidgetUpdater.requestUpdate(appContext)
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

    LoadFailureNotice(buildSet {
        if (state.error != null) add("cursor-usage")
        if (state.tasksError != null) add("cursor-tasks")
        if (state.statusError != null) add("cursor-status")
        if (state.usageHistoryError) add("cursor-observations")
        state.historyErrors.keys.forEach { add("cursor-history:$it") }
        if (state.historyError != null) add("cursor-history:${state.historyErrorKey}")
        if (codexState.error != null) add("codex-usage")
        if (codexState.statusError != null) add("codex-status")
        if (codexState.publicResetsError != null) add("public-resets")
        if (codexState.resetError != null) add("codex-reset-settings")
        if (codexState.usageHistoryError) add("codex-observations")
    }, sheetOpen, snackbarHostState)

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

    val currentState by rememberUpdatedState(state)
    val currentCodexState by rememberUpdatedState(codexState)
    val currentAccount by rememberUpdatedState(account)
    val currentSheetOpen by rememberUpdatedState(sheetOpen)
    val currentFirstFrameDrawn by rememberUpdatedState(firstFrameDrawn)
    LaunchedEffect(lifecycleOwner, viewModel, codexViewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Restore both local stores, and let surviving requests from a previous foreground finish.
            // Opening or closing settings does not create another foreground session.
            snapshotFlow {
                currentFirstFrameDrawn && currentState.stage == AppStage.Dashboard && !currentState.loadingAccounts &&
                    !currentState.submitting && !currentSheetOpen &&
                    !dashboardRefreshBusy(currentState, currentCodexState)
            }.first { it }
            val entryAccount = currentAccount
            val entryCredential = currentCodexState.credential
            val requests = buildList<RefreshRequest> {
                add { result -> viewModel.refreshStatus(false, true, result) }
                add { result -> codexViewModel.refreshStatus(false, result) }
                if (entryAccount != null && !entryAccount.tokenExpired) {
                    add { result -> viewModel.refreshUsage(false, true, result) }
                    add { result -> viewModel.refreshTasks(false, true, result) }
                }
                if (entryCredential != null && !entryCredential.expired && !entryCredential.invalid) {
                    add { result -> codexViewModel.refresh(false, result, includePublicResets = false) }
                }
            }
            if (refreshOnForeground(requests) && !currentSheetOpen &&
                currentAccount?.id == entryAccount?.id &&
                currentCodexState.credential?.updatedAt == entryCredential?.updatedAt &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                OperationFeedback.success()
            }
            // Subsequent polling stays silent and never restarts just because a sheet was dismissed.
            coroutineScope {
                launch {
                    while (true) {
                        delay(FOREGROUND_REFRESH_INTERVAL_MS)
                        if (!currentSheetOpen && !currentState.submitting && currentAccount?.tokenExpired == false) {
                            viewModel.refreshSelected(force = false, silent = true, includeStatus = false)
                        }
                    }
                }
                launch {
                    while (true) {
                        delay(FOREGROUND_REFRESH_INTERVAL_MS)
                        if (!currentSheetOpen) {
                            viewModel.refreshStatus(force = false, silent = true)
                            codexViewModel.refreshStatus(force = false)
                        }
                    }
                }
                launch {
                    while (true) {
                        delay(codexViewModel.nextRefreshDelayMillis())
                        val credential = currentCodexState.credential
                        if (!currentSheetOpen && !currentCodexState.saving && credential != null &&
                            !credential.expired && !credential.invalid) {
                            codexViewModel.refresh(force = false)
                        }
                    }
                }
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
            onVisibleTabChange = { visibleTab = it },
            allowPageWork = firstFrameDrawn,
            onHistoryDaysChange = { service, days ->
                if (service == "cursor") viewModel.setHistoryDays(days) else codexViewModel.setHistoryDays(days)
            },
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
                    viewModel.refreshUsage(true, true) { batch.finish("cursor-usage", it == RefreshResult.Updated) }
                    viewModel.refreshTasks(true, true) { batch.finish("cursor-tasks", it == RefreshResult.Updated) }
                }
                viewModel.refreshStatus(true, true) { batch.finish("cursor-status", it == RefreshResult.Updated) }
                codexViewModel.refreshStatus(true) { batch.finish("codex-status", it == RefreshResult.Updated) }
                if (codexConnected) codexViewModel.refresh(true, { batch.finish("codex-usage", it == RefreshResult.Updated) }, includePublicResets = false)
                if (publicLoaded) codexViewModel.refreshPublicResets(true) { batch.finish("public-resets", it) }
            },
            onRefreshCodex = { codexViewModel.refresh(true, { if (it == RefreshResult.Updated) OperationFeedback.success() }, includePublicResets = false) },
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
