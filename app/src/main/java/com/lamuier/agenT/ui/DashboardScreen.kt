package com.lamuier.agenT.ui

import android.provider.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.distinctUntilChanged

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.providers.*
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.model.AppUiState
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.ui.theme.PulseChartColors
import com.lamuier.agenT.util.BillingProgress
import com.lamuier.agenT.util.AgentTaskPresentation
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.SplitOverviewRingFocus
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.util.UsageLevel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlin.math.cos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 周期倒计时/百分比的本地走动间隔（仅重算时间，不触发网络请求）。 */
private const val BILLING_TICK_MS = 5_000L

/** 自有 / 三方外环一次完整呼吸周期（自有最亮 → 三方最亮 → 回到自有）。 */
private const val PAIRED_RING_BREATH_MS = 3_000

/** 呼吸谷值透明度：不要到 0，避免另一侧完全消失后突然跳出来。 */
private const val PAIRED_RING_MIN_ALPHA = 0.08f

@Immutable
private data class MetricTile(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val accent: Color,
)

private enum class DashboardRefreshSource {
    Pull,
    Button,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    state: AppUiState,
    codexState: CodexQuotaUiState,
    snackbarHostState: SnackbarHostState,
    tabOrder: List<DashboardTab>,
    pendingOpenTab: DashboardTab? = null,
    onVisibleTabChange: (DashboardTab) -> Unit = {},
    allowPageWork: Boolean = true,
    onHistoryDaysChange: (String, Int) -> Unit = { _, _ -> },
    onOpenTabConsumed: () -> Unit = {},
    onLoadHistoryWindow: (String, Long, Long, String?) -> Unit,
    onRefresh: () -> Unit,
    onRefreshCodex: () -> Unit,
    onManageCodex: () -> Unit,
    onManageAccount: () -> Unit,
    onShowSettings: () -> Unit,
    onShowTokenHelp: () -> Unit,
    onSubscriptionDay: (Int) -> Unit = {},
    onRefreshPublicResets: (Boolean) -> Unit = {},
    providers: ProvidersUiState = ProvidersUiState(false),
    onManageProvider: (UsageProvider) -> Unit = {}, onRefreshProvider: (UsageProvider) -> Unit = {},
) {
    val uiScope = rememberCoroutineScope()
    val openCursorUrl = rememberOpenCursorUrl { message ->
        uiScope.launch { snackbarHostState.showSnackbar(message) }
    }
    val feedback = LocalAppFeedback.current
    val tabs = remember(tabOrder) { DashboardTab.resolveOrder(tabOrder.map { it.id }) }
    var selectedTabId by rememberSaveable { mutableStateOf(pendingOpenTab?.id ?: tabs.first().id) }
    val selectedTab = DashboardTab.fromId(selectedTabId) ?: tabs.first()
    LaunchedEffect(selectedTab) { onVisibleTabChange(selectedTab) }
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(selectedTab).coerceAtLeast(0), pageCount = { tabs.size })
    val pageStates = rememberSaveableStateHolder()
    val dragging by pagerState.interactionSource.collectIsDraggedAsState()
    var userDragged by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) userDragged = true }
    LaunchedEffect(pendingOpenTab, tabs) {
        val target = pendingOpenTab ?: DashboardTab.fromId(selectedTabId) ?: tabs.first()
        val index = tabs.indexOf(target).coerceAtLeast(0)
        selectedTabId = tabs[index].id
        pagerState.scrollToPage(index)
        if (pendingOpenTab != null) onOpenTabConsumed()
    }
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { page ->
            val id = currentTabs.getOrNull(page)?.id ?: return@collect
            if (id != selectedTabId) {
                selectedTabId = id
                if (userDragged) feedback.selection()
            }
            userDragged = false
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val refreshBusy = dashboardRefreshBusy(state, codexState) || providers.busy
    val refreshBusyUpdated by rememberUpdatedState(refreshBusy)
    var refreshSource by remember { mutableStateOf<DashboardRefreshSource?>(null) }
    val startRefresh: (DashboardRefreshSource) -> Unit = { source ->
        if (!state.submitting && !refreshBusy && refreshSource == null) {
            refreshSource = source
            onRefresh()
        }
    }
    LaunchedEffect(refreshBusy) { if (!refreshBusy) refreshSource = null }
    LaunchedEffect(refreshSource) {
        if (refreshSource != null) { delay(400); if (!refreshBusyUpdated) refreshSource = null }
    }
    val openTab: (Int) -> Unit = { index ->
        if (selectedTabId != tabs[index].id) {
            feedback.selection()
            userDragged = false
            selectedTabId = tabs[index].id
            uiScope.launch { pagerState.animateScrollToPage(index) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp && maxHeight >= 480.dp &&
        LocalWindowFolds.current.none { it.width > it.height || it.left < with(LocalDensity.current) { 80.dp.toPx() } }
    val compactLabels = LocalDensity.current.fontScale > 1.3f && maxWidth < 600.dp
    Row(Modifier.fillMaxSize()) {
        if (useRail) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                windowInsets = NavigationRailDefaults.windowInsets,
            ) {
                Spacer(Modifier.weight(1f))
                tabs.forEachIndexed { index, tab ->
                    DashboardRailItem(tab, selectedTabId == tab.id) { openTab(index) }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentWindowInsets = if (useRail) {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)
            } else WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        tabs.forEachIndexed { index, tab ->
                            DashboardBarItem(tab, selectedTabId == tab.id, compactLabels) { openTab(index) }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                FoldSafeAppBar {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(selectedTab.labelRes), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                DashboardTabScopeBadge(selectedTab.scope)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (selectedTab == DashboardTab.Tasks) {
                            IconButton(onClick = { openCursorUrl(AgentTaskPresentation.agentsPageUrl()) }) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.tasks_create_on_web))
                            }
                        }
                        val showRefreshProgress = refreshBusy || refreshSource == DashboardRefreshSource.Button
                        val refreshDescription = stringResource(if (showRefreshProgress) R.string.action_refreshing else R.string.action_refresh)
                        IconButton(modifier = Modifier.semantics { contentDescription = refreshDescription },
                            enabled = !state.loadingAccounts && !refreshBusy && refreshSource == null && !state.submitting,
                            onClick = { startRefresh(DashboardRefreshSource.Button) }) {
                            if (showRefreshProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            } else Icon(painterResource(R.drawable.symbol_refresh), contentDescription = null)
                        }
                        IconButton(enabled = !state.loadingAccounts && !state.submitting, onClick = { feedback.selection(); onShowSettings() }) {
                            Icon(painterResource(R.drawable.symbol_settings), stringResource(R.string.action_settings))
                        }
                    },
                )
                }
            },
        ) { padding ->
            if (state.loadingAccounts && selectedTab != DashboardTab.Tasks) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    DashboardState(icon = null, title = stringResource(R.string.dashboard_loading_accounts_title),
                        description = stringResource(R.string.dashboard_loading_accounts_body), loading = true)
                }
            } else {
                PullToRefreshBox(isRefreshing = refreshSource == DashboardRefreshSource.Pull,
                    onRefresh = { startRefresh(DashboardRefreshSource.Pull) },
                    modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
                    HorizontalPager(state = pagerState, key = { tabs[it].id }, modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = if (allowPageWork) 1 else 0) { page ->
                        pageStates.SaveableStateProvider(tabs[page].id) {
                                when (tabs.getOrNull(page) ?: DashboardTab.Overview) {
                                    DashboardTab.Overview -> UnifiedOverviewTab(
                                        state = state, codex = codexState, onOpenSettings = onShowSettings,
                                        providers = providers, onManageProvider = onManageProvider,
                                        onManageCursor = onManageAccount, onManageCodex = onManageCodex,
                                        onOpenTab = { target ->
                                            if (selectedTabId != target.id) {
                                                feedback.selection(); userDragged = false; selectedTabId = target.id
                                                uiScope.launch { pagerState.animateScrollToPage(tabs.indexOf(target)) }
                                            }
                                        },
                                    )
                                    DashboardTab.Usage -> UsageScreen(
                                        state, codexState, onLoadHistoryWindow,
                                        onRefresh = { startRefresh(DashboardRefreshSource.Button) },
                                        onRefreshCodex = onRefreshCodex,
                                        onManageCursor = onManageAccount, onManageCodex = onManageCodex,
                                        active = allowPageWork && selectedTab == DashboardTab.Usage,
                                        providers = providers, onManageProvider = onManageProvider, onRefreshProvider = onRefreshProvider,
                                        onHistoryDaysChange = onHistoryDaysChange,
                                        onOpenFailed = { message -> uiScope.launch { snackbarHostState.showSnackbar(message) } },
                                    )
                                    DashboardTab.Tasks -> AccountOnlyTab(state, onManageAccount, onShowTokenHelp) {
                                        TasksTab(
                                            tasks = state.tasks,
                                            loading = state.loadingTasks,
                                            refreshing = state.refreshingTasks,
                                            error = state.tasksError,
                                            onRetry = { startRefresh(DashboardRefreshSource.Button) },
                                            onOpenFailed = { message -> uiScope.launch { snackbarHostState.showSnackbar(message) } },
                                        )
                                    }
                                    DashboardTab.Resets -> CodexResetTab(codexState, onSubscriptionDay, onRefreshPublicResets,
                                        active = allowPageWork && selectedTab == DashboardTab.Resets)
                                    DashboardTab.Status -> StatusTab(
                                        active = selectedTab == DashboardTab.Status,
                                        status = state.serviceStatus,
                                        loading = state.loadingStatus,
                                        refreshing = state.refreshingStatus,
                                        error = state.statusError,
                                        codexState = codexState, providers = providers,
                                        cursorConfigured = state.accounts.isNotEmpty(),
                                        cursorLoading = state.loadingAccounts,
                                        onRetry = { startRefresh(DashboardRefreshSource.Button) },
                                        onConfigure = onManageAccount,
                                        onOpenFailed = { message ->
                                            uiScope.launch {
                                                snackbarHostState.showSnackbar(message)
                                            }
                                        },
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun DashboardNavLabel(tab: DashboardTab, compact: Boolean) {
    val fullLabel = stringResource(tab.labelRes)
    Text(stringResource(if (compact) tab.compactLabel else tab.labelRes), maxLines = 2,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
        modifier = Modifier.semantics { contentDescription = fullLabel })
}

@Composable
private fun DashboardRailItem(tab: DashboardTab, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(selected = selected, onClick = onClick,
        icon = { Icon(painterResource(tab.navigationIcon), contentDescription = null) },
        label = { DashboardNavLabel(tab, compact = false) })
}

@Composable
private fun RowScope.DashboardBarItem(tab: DashboardTab, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick,
        icon = { Icon(painterResource(tab.navigationIcon), contentDescription = null) },
        label = { DashboardNavLabel(tab, compact) })
}

internal val DashboardTab.compactLabel: Int get() = when(this) {
    DashboardTab.Overview -> R.string.tab_overview_compact
    DashboardTab.Usage -> R.string.tab_usage_compact
    DashboardTab.Tasks -> R.string.tab_tasks_compact
    DashboardTab.Resets -> R.string.tab_resets_compact
    DashboardTab.Status -> R.string.tab_status_compact
}

internal val DashboardTab.navigationIcon: Int get() = when(this) {
    DashboardTab.Overview -> R.drawable.symbol_dashboard
    DashboardTab.Usage -> R.drawable.symbol_donut_large
    DashboardTab.Tasks -> R.drawable.symbol_smart_toy
    DashboardTab.Resets -> R.drawable.symbol_history
    DashboardTab.Status -> R.drawable.symbol_monitor_heart
}


@Composable
internal fun ProviderStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    loading: Boolean = false,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    error: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface,
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
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    Button(onClick = onPrimaryAction, modifier = Modifier.padding(top = 3.dp)) {
                        Text(primaryActionLabel)
                    }
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                }
            }
        }
    }
}

@Composable
private fun AccountOnlyTab(
    state: AppUiState,
    onManageAccount: () -> Unit,
    onShowTokenHelp: () -> Unit,
    content: @Composable () -> Unit,
) {
    val account = state.accounts.firstOrNull { it.id == state.selectedAccountId }
        ?: state.accounts.firstOrNull()
    when {
        state.loadingAccounts -> DashboardState(icon = null,
            title = stringResource(R.string.dashboard_loading_accounts_title),
            description = stringResource(R.string.dashboard_loading_accounts_body), loading = true)
        account == null -> DashboardState(
            icon = Icons.Outlined.PersonAdd,
            title = stringResource(R.string.dashboard_add_account_title),
            description = stringResource(R.string.dashboard_add_account_tasks_body),
            primaryActionLabel = stringResource(R.string.action_add_account),
            onPrimaryAction = onManageAccount,
            secondaryActionLabel = stringResource(R.string.action_token_help),
            onSecondaryAction = onShowTokenHelp,
        )
        account.tokenExpired -> DashboardState(
            icon = Icons.Outlined.KeyOff,
            title = stringResource(R.string.dashboard_token_expired_title),
            description = stringResource(R.string.dashboard_token_expired_tasks_body),
            primaryActionLabel = stringResource(R.string.action_update_token),
            onPrimaryAction = onManageAccount,
            secondaryActionLabel = stringResource(R.string.action_token_help_short),
            onSecondaryAction = onShowTokenHelp,
        )
        else -> content()
    }
}

@Composable
private fun AccountStatusRow(account: CursorAccount?, loading: Boolean) {
    when {
        loading -> Text(
            stringResource(R.string.dashboard_account_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        account == null -> Text(
            stringResource(R.string.dashboard_account_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        account.tokenExpired -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                account.alias,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusChip(label = stringResource(R.string.dashboard_token_expired_chip), error = true)
        }
        else -> Text(
            account.alias,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StatusChip(label: String, error: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DashboardState(
    icon: ImageVector?,
    title: String,
    description: String,
    loading: Boolean = false,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    HingeSafeLayout {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
                icon != null -> {
                    val badgeBrush = Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(badgeBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                else -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryActionLabel)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
    }
}

@Composable
internal fun OverviewCycleRow(billing: BillingProgress?, planCycleEnd: String?) {
    val cycleRange = when {
        billing != null -> billing.rangeLabel
        !planCycleEnd.isNullOrBlank() ->
            stringResource(
                R.string.label_ends_at,
                DisplayTime.formatStoredDateTime(planCycleEnd, LocalDisplayZone.current) ?: planCycleEnd.take(10),
            )
        else -> stringResource(R.string.label_cycle_unknown)
    }
    val remaining = billing?.let {
        stringResource(R.string.label_remaining, formatRemainingLabel(it.remainingMillis))
    }
    val description = listOfNotNull(
        billing?.let {
            stringResource(
                R.string.label_cycle_progress_a11y,
                formatPercent(it.percent.toDouble()),
                formatRemainingLabel(it.remainingMillis),
            )
        } ?: stringResource(R.string.label_billing_cycle),
        cycleRange,
    ).joinToString("\n")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (remaining != null) {
            Text(
                remaining,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (billing != null) {
            CycleRangeMark(billing.range)
        } else {
            Text(
                cycleRange,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CycleRangeMark(range: DisplayTime.RangeParts) {
    val dateStyle = MaterialTheme.typography.titleSmall.copy(
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    )
    val metaStyle = MaterialTheme.typography.labelSmall.copy(
        fontFeatureSettings = "tnum",
        letterSpacing = 0.3.sp,
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                range.startDate,
                style = dateStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            )
            Text(
                range.endDate,
                style = dateStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val meta = listOfNotNull(range.endTime, range.offset).joinToString("  ")
        Text(
            meta,
            style = metaStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CyclePercentDotLabel(billing: BillingProgress?, color: Color) {
    DotLabel(
        color = color,
        text = billing?.let {
            stringResource(R.string.label_cycle_percent, formatPercent(it.percent.toDouble()))
        } ?: stringResource(R.string.label_cycle_placeholder),
    )
}

@Composable
private fun DotLabel(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FreshnessRow(usage: AgenTOverview) {
    val stamp = DisplayTime.formatStoredClock(usage.fetchedAt, LocalDisplayZone.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (usage.fromCache) Icons.Outlined.Cached else Icons.Outlined.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(
                stringResource(if (usage.fromCache) R.string.freshness_cached else R.string.freshness_updated),
                stamp,
                stringResource(R.string.freshness_pull_hint),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
internal fun AdaptiveTabContent(scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.(compact: Boolean) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 620.dp || maxWidth < 360.dp || fontScale > 1.15f
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val enterAlpha by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "tab enter alpha",
        )
        val enterOffset by animateFloatAsState(
            targetValue = if (entered) 0f else 18f,
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "tab enter offset",
        )
        Column(
            modifier = Modifier
                .widthIn(max = 1200.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = enterAlpha
                    translationY = enterOffset
                }
                .verticalScroll(scrollState)
                .padding(
                    horizontal = if (compact) 12.dp else 16.dp,
                    vertical = if (compact) 10.dp else 14.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            content(compact)
        }
    }
}

@Composable
internal fun AdaptiveLazyTabContent(state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.(Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 360.dp || maxHeight < 620.dp || LocalDensity.current.fontScale > 1.15f
        androidx.compose.foundation.lazy.LazyColumn(
            state = state,
            modifier = modifier.widthIn(max = 1200.dp).fillMaxSize().align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(if (compact) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) { content(compact) }
    }
}

@Composable
internal fun SectionHeading(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun AnimatedValueText(
    value: String,
    style: TextStyle,
    fontWeight: FontWeight? = null,
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (
                fadeIn(tween(180)) +
                    slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 4 }
                ).togetherWith(
                fadeOut(tween(100)) + slideOutVertically(tween(140)) { -it / 5 },
            )
        },
        label = "metric value",
    ) { text ->
        Text(
            text,
            style = style,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OverviewHeroUsageRing(
    usage: AgenTOverview,
    billing: BillingProgress?,
    chartSize: Dp,
    chartColors: PulseChartColors,
) {
    val cyclePercent = billing?.percent?.toDouble()
    val cycleColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewSplitUsageRing(
            usage = usage,
            billing = billing,
            cyclePercent = cyclePercent,
            cycleColor = cycleColor,
            chartSize = chartSize,
            chartColors = chartColors,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewSplitUsageRing(
    usage: AgenTOverview,
    billing: BillingProgress?,
    cyclePercent: Double?,
    cycleColor: Color,
    chartSize: Dp,
    chartColors: PulseChartColors,
) {
    val ownPercent = usage.usage.autoPercentUsed
    val thirdPartyPercent = usage.usage.apiPercentUsed
    val focus = UsageCalculations.splitOverviewRingFocus(ownPercent, thirdPartyPercent)
    val showOwn = focus != SplitOverviewRingFocus.ThirdPartyOnly
    val showThird = focus != SplitOverviewRingFocus.OwnOnly
    val soloThird = focus == SplitOverviewRingFocus.ThirdPartyOnly
    val ownLevel = ownPercent?.let { UsageCalculations.level(it, cyclePercent) }
    val thirdPartyLevel = thirdPartyPercent?.let { UsageCalculations.level(it, cyclePercent) }
    val unknownColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ownColor = ownLevel.ringColor(chartColors, unknownColor)
    val thirdPartyHealth = thirdPartyLevel.ringColor(chartColors, unknownColor)
    val thirdPartyColor = if (showOwn && thirdPartyHealth == ownColor) chartColors.chart3 else thirdPartyHealth
    val ownLabel = stringResource(R.string.label_own_pool)
    val thirdPartyLabelShort = stringResource(R.string.label_third_party_pool)
    val exhaustedCaption = when (focus) {
        SplitOverviewRingFocus.ThirdPartyOnly -> stringResource(R.string.overview_ring_own_exhausted_caption)
        SplitOverviewRingFocus.OwnOnly -> stringResource(R.string.overview_ring_third_party_exhausted_caption)
        SplitOverviewRingFocus.Both -> null
    }
    val cycleDescription = billing?.let {
        stringResource(R.string.label_cycle_percent, formatPercent(it.percent.toDouble())) +
            stringResource(R.string.label_cycle_remaining_clause, formatRemainingLabel(it.remainingMillis))
    } ?: stringResource(R.string.label_cycle_placeholder)
    val ringDescription = buildList {
        if (showOwn) {
            add(
                poolRingDescription(
                    poolLabel = ownLabel,
                    percent = ownPercent,
                    level = ownLevel,
                ),
            )
        }
        if (showThird) {
            add(
                poolRingDescription(
                    poolLabel = thirdPartyLabelShort,
                    percent = thirdPartyPercent,
                    level = thirdPartyLevel,
                ),
            )
        }
        exhaustedCaption?.let(::add)
        add(cycleDescription)
    }.joinToString("。")
    val teamSpendLine = if (usage.isTeam) {
        stringResource(R.string.label_team_spend, money(usage.usage.totalUsed))
    } else {
        null
    }
    val centerPercent = if (soloThird) thirdPartyPercent else ownPercent
    val centerLabel = if (soloThird) thirdPartyLabelShort else ownLabel
    val centerColor = if (soloThird) thirdPartyColor else ownColor
    UsageRing(
        ownPercent = ownPercent?.takeIf { showOwn }?.toFloat(),
        ownColor = ownColor,
        thirdPartyPercent = thirdPartyPercent?.takeIf { showThird }?.toFloat(),
        thirdPartyColor = thirdPartyColor,
        cyclePercent = billing?.percent,
        cycleColor = cycleColor,
        centerValue = centerPercent?.let(::formatPercent) ?: "—",
        caption = centerLabel,
        captionColor = centerColor,
        description = ringDescription,
        size = chartSize,
    )
    exhaustedCaption?.let { caption ->
        Text(
            caption,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showOwn) {
            DotLabel(
                color = ownColor,
                text = ownPercent?.let { stringResource(R.string.label_own_pool_usage, formatPercent(it)) }
                    ?: stringResource(R.string.label_own_pool_usage_placeholder),
            )
        }
        if (showThird) {
            DotLabel(
                color = thirdPartyColor,
                text = thirdPartyPercent?.let {
                    stringResource(R.string.label_third_party_usage, formatPercent(it))
                } ?: stringResource(R.string.label_third_party_usage_placeholder),
            )
        }
        CyclePercentDotLabel(billing = billing, color = cycleColor)
    }
    teamSpendLine?.let { spend ->
        Text(
            spend,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageRing(
    ownPercent: Float?,
    ownColor: Color,
    thirdPartyPercent: Float?,
    thirdPartyColor: Color,
    cyclePercent: Float?,
    cycleColor: Color,
    centerValue: String,
    caption: String,
    captionColor: Color,
    description: String,
    size: Dp,
    valueStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val ownSweep = rememberRingSweep(ownPercent, "own ring")
    val thirdPartySweep = rememberRingSweep(thirdPartyPercent, "third-party ring")
    val cycleSweep = rememberRingSweep(cyclePercent, "cycle ring")
    val ringAlphas = rememberPairedRingAlphas(
        enabled = (ownPercent ?: 0f) > 0f && (thirdPartyPercent ?: 0f) > 0f,
    )
    Box(
        modifier = Modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerStroke = size.toPx() * 0.1f
            val innerStroke = size.toPx() * 0.055f
            val gap = size.toPx() * 0.028f
            val outerInset = outerStroke / 2f
            drawPairedUsageBand(
                inset = outerInset,
                stroke = outerStroke,
                ownSweep = ownSweep,
                ownColor = ownColor,
                ownAlpha = ringAlphas.own,
                thirdPartySweep = thirdPartySweep,
                thirdPartyColor = thirdPartyColor,
                thirdPartyAlpha = ringAlphas.thirdParty,
                trackColor = trackColor,
            )
            val innerInset = outerStroke + gap + innerStroke / 2f
            drawConcentricBand(
                inset = innerInset,
                stroke = innerStroke,
                sweep = cycleSweep,
                color = cycleColor,
                trackColor = trackColor.copy(alpha = 0.55f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedValueText(
                value = centerValue,
                style = valueStyle,
                fontWeight = FontWeight.Bold,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = captionColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun rememberPairedRingAlphas(enabled: Boolean): PairedRingAlphas {
    val animationsOn = rememberAnimationsEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val phase = remember { Animatable(0f) }
    LaunchedEffect(enabled, animationsOn, lifecycleOwner) {
        if (!enabled || !animationsOn) {
            phase.snapTo(0f)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            phase.snapTo(0f)
            while (true) {
                phase.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(PAIRED_RING_BREATH_MS, easing = LinearEasing),
                )
                phase.snapTo(0f)
                delay(16) // Also yields if the system changes animation scale to zero mid-frame.
            }
        }
    }
    return when {
        !enabled || !animationsOn -> PairedRingAlphas.Opaque
        else -> pairedRingBreathAlphas(phase.value)
    }
}

@Composable
private fun rememberRingSweep(percent: Float?, label: String): Float {
    val target = percent?.coerceIn(0f, 100f)?.div(100f)?.times(360f) ?: 0f
    val sweep by animateFloatAsState(
        targetValue = target,
        animationSpec = com.lamuier.agenT.ui.theme.AgenTMotion.content(),
        label = label,
    )
    return sweep
}

private data class PairedRingAlphas(
    val own: Float,
    val thirdParty: Float,
) {
    companion object {
        val Opaque = PairedRingAlphas(1f, 1f)
    }
}

private fun pairedRingBreathAlphas(
    phase: Float,
    minAlpha: Float = PAIRED_RING_MIN_ALPHA,
): PairedRingAlphas {
    val wave = 0.5f + 0.5f * cos((phase * 2.0 * Math.PI).toFloat())
    val span = 1f - minAlpha
    return PairedRingAlphas(
        own = minAlpha + span * wave,
        thirdParty = minAlpha + span * (1f - wave),
    )
}

private data class PairedRingLayer(
    val sweep: Float,
    val color: Color,
    val alpha: Float,
)

private fun DrawScope.drawPairedUsageBand(
    inset: Float,
    stroke: Float,
    ownSweep: Float,
    ownColor: Color,
    ownAlpha: Float,
    thirdPartySweep: Float,
    thirdPartyColor: Color,
    thirdPartyAlpha: Float,
    trackColor: Color,
) {
    val arc = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
    val origin = Offset(inset, inset)
    drawArc(
        color = trackColor,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    val layers = buildList {
        if (ownSweep > 0.5f && ownAlpha > 0.01f) {
            add(PairedRingLayer(ownSweep, ownColor, ownAlpha.coerceIn(0f, 1f)))
        }
        if (thirdPartySweep > 0.5f && thirdPartyAlpha > 0.01f) {
            add(PairedRingLayer(thirdPartySweep, thirdPartyColor, thirdPartyAlpha.coerceIn(0f, 1f)))
        }
    }.sortedWith(
        // 较长的先画；较短的永远叠在上面，透明度变化才看得清。
        // 若按透明度排序，较短那段在变暗时会被较长那段整段盖住，呼吸会像闪断。
        compareByDescending<PairedRingLayer> { it.sweep }.thenBy { it.alpha },
    )
    layers.forEach { layer ->
        drawUsageProgressArc(
            origin = origin,
            arc = arc,
            stroke = stroke,
            sweep = layer.sweep,
            color = layer.color,
            alpha = layer.alpha,
        )
    }
}

private fun DrawScope.drawUsageProgressArc(
    origin: Offset,
    arc: Size,
    stroke: Float,
    sweep: Float,
    color: Color,
    alpha: Float,
) {
    if (sweep <= 0.5f || alpha <= 0.01f) return
    drawArc(
        color = color,
        alpha = 0.16f * alpha,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke * 1.5f, cap = StrokeCap.Round),
    )
    drawArc(
        brush = Brush.linearGradient(listOf(color.copy(alpha = 0.6f), color)),
        alpha = alpha,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawConcentricBand(
    inset: Float,
    stroke: Float,
    sweep: Float,
    color: Color,
    trackColor: Color,
) {
    val arc = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
    val origin = Offset(inset, inset)
    drawArc(
        color = trackColor,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    if (sweep <= 0f) return
    drawArc(
        color = color,
        alpha = 0.16f,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke * 1.5f, cap = StrokeCap.Round),
    )
    drawArc(
        brush = Brush.linearGradient(listOf(color.copy(alpha = 0.6f), color)),
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = origin,
        size = arc,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

@Composable
private fun MetricRowCard(tiles: List<MetricTile>, compact: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            tiles.forEach { tile ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(tile.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                tile.icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = tile.accent,
                            )
                        }
                        Text(
                            tile.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnimatedValueText(
                        value = tile.value,
                        style = if (compact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    color: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val fill = Brush.horizontalGradient(listOf(color.copy(alpha = 0.62f), color))
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        val width = size.width * progress.coerceIn(0f, 1f)
        if (width > 0f) {
            drawRoundRect(
                brush = fill,
                size = Size(width, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * 页面可见期间每 [BILLING_TICK_MS] 返回一次最新时间，驱动周期百分比/倒计时本地走动；
 * 后台自动暂停，不触发网络请求。
 */
@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(BILLING_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }
    return now
}

@Composable
private fun formatRemainingLabel(millis: Long, compact: Boolean = false): String =
    UsageCalculations.formatRemaining(millis, compact, LocalContext.current.resources)

@Composable
private fun UsageLevel.label(): String = stringResource(
    when (this) {
        UsageLevel.Healthy -> R.string.usage_level_healthy
        UsageLevel.Warning -> R.string.usage_level_warning
        UsageLevel.Critical -> R.string.usage_level_critical
        UsageLevel.Exhausted -> R.string.usage_level_exhausted
    },
)

private fun UsageLevel?.ringColor(chartColors: PulseChartColors, unknown: Color): Color = when (this) {
    UsageLevel.Healthy -> chartColors.healthy
    UsageLevel.Warning -> chartColors.warning
    UsageLevel.Critical, UsageLevel.Exhausted -> chartColors.critical
    null -> unknown
}

@Composable
private fun poolRingDescription(
    poolLabel: String,
    percent: Double?,
    level: UsageLevel?,
): String = if (percent == null) {
    stringResource(R.string.label_pool_usage_a11y_unknown, poolLabel)
} else {
    stringResource(
        R.string.label_pool_usage_a11y,
        poolLabel,
        formatPercent(percent),
        level?.label() ?: stringResource(R.string.usage_level_unknown),
    )
}

private fun Double.safeNonNegative(): Double = takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

private fun Double.visualPercent(): Double = safeNonNegative().coerceAtMost(100.0)

private fun Double.toProgress(): Float = (visualPercent() / 100.0).toFloat()

private fun formatPercent(value: Double): String =
    String.format(Locale.US, "%.2f%%", value.safeNonNegative())

private fun money(value: Double): String =
    String.format(Locale.US, "\$%.2f", value.safeNonNegative())
