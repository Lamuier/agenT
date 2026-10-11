package com.lamuier.agenT.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.lamuier.agenT.data.OverviewUsageRingMode
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
    overviewUsageRingMode: OverviewUsageRingMode,
    onOverviewUsageRingModeChange: (OverviewUsageRingMode) -> Unit,
    onCodexResetRemindersToggle: (Boolean) -> Unit = {},
    onCatLanguageToggle: (Boolean) -> Unit = {},
    codexUsageAutoRefresh: Boolean = true,
    onCodexUsageAutoRefreshChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val codexState by codexViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val account = remember(state.accounts, state.selectedAccountId) {
        state.accounts.firstOrNull { it.id == state.selectedAccountId }
            ?: state.accounts.firstOrNull()
    }

    var manageTokens by remember { mutableStateOf(false) }
    var tokenProvider by remember { mutableStateOf(com.lamuier.agenT.model.TokenProvider.Cursor) }
    var showTokenHelp by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var returnToSettings by remember { mutableStateOf(false) }
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
        AppStage.Booting -> BootScreen()
        AppStage.Dashboard -> DashboardScreen(
            state = state,
            codexState = codexState,
            snackbarHostState = snackbarHostState,
            tabOrder = tabOrder,
            pendingOpenTab = pendingOpenTab,
            onOpenTabConsumed = onOpenTabConsumed,
            onLoadHistoryWindow = viewModel::loadHistoryWindow,
            onRefresh = {
                viewModel.refreshSelected(force = true, silent = true)
                codexViewModel.refresh(force = true)
                codexViewModel.refreshStatus(force = true)
            },
            onRefreshCodex = { codexViewModel.refresh(force = true) },
            onManageCodex = { openTokenManagement(preferred = com.lamuier.agenT.model.TokenProvider.Codex) },
            onManageAccount = { openTokenManagement(preferred = com.lamuier.agenT.model.TokenProvider.Cursor) },
            onShowSettings = { showSettings = true },
            onShowTokenHelp = { showTokenHelp = true },
            onRegisterReset = codexViewModel::registerReset,
            onDeleteReset = codexViewModel::deleteReset,
            onSubscriptionDay = codexViewModel::setSubscriptionDay,
            onRefreshPublicResets = codexViewModel::refreshPublicResets,
        )
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
                if (provider == com.lamuier.agenT.model.TokenProvider.Cursor) {
                    val current = account
                    if (current == null) viewModel.addAccount(metadata, token, done)
                    else viewModel.updateAccount(current.id, metadata.takeIf { it != current.alias }, token.ifBlank { null }, done)
                } else codexViewModel.saveCredential(token, metadata, done)
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
            overviewUsageRingMode = overviewUsageRingMode,
            onOverviewUsageRingModeChange = onOverviewUsageRingModeChange,
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
        AlertDialog(
            onDismissRequest = { if (!codexState.saving) showCodexDeleteDialog = false },
            title = { Text(stringResource(R.string.codex_delete_token_title)) },
            text = { Text(stringResource(R.string.codex_delete_token_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        codexViewModel.clearCredential {
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
        AlertDialog(
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


@Composable
internal fun BootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.boot_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(28.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
