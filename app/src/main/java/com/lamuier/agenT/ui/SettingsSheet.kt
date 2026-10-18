package com.lamuier.agenT.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import kotlinx.coroutines.CancellationException

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.data.NotificationSettings
import com.lamuier.agenT.notification.IslandMonitorState
import com.lamuier.agenT.notification.NotificationCopy
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.DisplayTimeZones
import java.time.Instant

internal enum class SettingsSection(val title: Int, val icon: ImageVector) {
    Connections(R.string.settings_connections_title, Icons.Outlined.Key),
    Appearance(R.string.appearance_title, Icons.Outlined.DarkMode),
    Notifications(R.string.notification_section_title, Icons.Outlined.Notifications),
    SystemEntries(R.string.settings_system_entries, Icons.Outlined.Widgets),
    General(R.string.settings_general, Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    settings: ThemeSettings,
    notificationSettings: NotificationSettings,
    onDismiss: () -> Unit,
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
    onManageTokens: () -> Unit,
    cursorAccount: CursorAccount?,
    codexState: CodexQuotaUiState,
    onCodexResetRemindersToggle: (Boolean) -> Unit = {},
    onCatLanguageToggle: (Boolean) -> Unit = {},
    sectionId: String? = null,
    onSectionChange: ((String?) -> Unit)? = null,
    hapticFeedbackEnabled: Boolean = true,
    onHapticFeedbackChange: (Boolean) -> Unit = {},
    codexUsageAutoRefresh: Boolean = true,
    onCodexUsageAutoRefreshChange: (Boolean) -> Unit = {},
    providers: com.lamuier.agenT.providers.ProvidersUiState = com.lamuier.agenT.providers.ProvidersUiState(false),
) {
    val connectionResources = LocalResources.current
    val systemPaletteAvailable = Build.VERSION.SDK_INT >= 31
    var localSection by rememberSaveable { mutableStateOf<String?>(null) }
    val currentId = if (onSectionChange != null) sectionId else localSection
    val feedback = LocalAppFeedback.current
    var previousHaptics by remember { mutableStateOf(hapticFeedbackEnabled) }
    LaunchedEffect(hapticFeedbackEnabled) {
        if (hapticFeedbackEnabled && !previousHaptics) feedback.toggle(true)
        previousHaptics = hapticFeedbackEnabled
    }
    fun select(id: String?) {
        if (onSectionChange != null) onSectionChange(id) else localSection = id
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val folds = LocalWindowFolds.current
        val wide = with(density) {
            paneLayout(androidx.compose.ui.geometry.Rect(0f, 0f, maxWidth.toPx(), (maxHeight - 64.dp).toPx()), folds,
                (320f * fontScale.coerceAtLeast(1f)).dp.toPx(), (160f * fontScale.coerceAtLeast(1f)).dp.toPx(),
                16.dp.toPx(), maxWidth >= 760.dp || folds.isNotEmpty()).paired
        }
        val categoriesScroll = rememberScrollState()
        val detailScroll = rememberScrollState()
        val explicitCurrent = SettingsSection.entries.firstOrNull { it.name == currentId }
        val current = explicitCurrent ?: if (wide) SettingsSection.Connections else null
        fun back() { if (!wide && explicitCurrent != null) select(null) else onDismiss() }
        var backProgress by remember { mutableFloatStateOf(0f) }
        PredictiveBackHandler { events ->
            try { events.collect { backProgress = it.progress }; back() }
            catch (_: CancellationException) { /* Canceled gesture leaves the route intact. */ }
            finally { backProgress = 0f }
        }
        val paneWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Scaffold(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = paneWidthPx * 0.08f * backProgress
                scaleX = 1f - 0.02f * backProgress
                scaleY = scaleX
            },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                FoldSafeAppBar {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(if (wide || current == null) R.string.settings_title else current.title)) },
                    navigationIcon = { IconButton(onClick = ::back) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.symbol_arrow_back), stringResource(R.string.action_back))
                    } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                }
            },
        ) { padding ->
            val categories: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize()
                        .verticalScroll(categoriesScroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_description), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                        SettingsSection.entries.forEach { item ->
                            SettingNavRow(stringResource(item.title), stringResource(when(item) {
                                SettingsSection.Connections -> R.string.settings_connections_description
                                SettingsSection.Appearance -> R.string.appearance_description
                                SettingsSection.Notifications -> R.string.settings_notifications_summary
                                SettingsSection.SystemEntries -> R.string.settings_system_entries_summary
                                SettingsSection.General -> R.string.settings_general_summary
                            }), item.icon, selected = current == item, onClick = { if (current != item) { feedback.selection(); select(item.name) } })
                        }
                    }
            }
            val detail: @Composable () -> Unit = {
                    Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(detailScroll)
                        .imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        when (current ?: SettingsSection.Connections) {
                            SettingsSection.Connections -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.settings_connections_title))
                Text(
                    stringResource(R.string.settings_connections_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingNavRow(
                    label = stringResource(R.string.token_management_title),
                    description = stringResource(R.string.token_management_connection_summary,
                        stringResource(when {
                            cursorAccount == null -> R.string.connection_not_configured
                            cursorAccount.tokenExpired -> R.string.connection_token_expired
                            else -> R.string.token_management_saved
                        }),
                        stringResource(when {
                            codexState.credential == null -> R.string.connection_not_configured
                            codexState.credential.expired || codexState.credential.invalid -> R.string.connection_token_expired
                            else -> R.string.token_management_saved
                        })) + "\n" + com.lamuier.agenT.providers.UsageProvider.entries.joinToString(" · ") { source ->
                            source.title + ": " + connectionResources.getString(if (providers[source].credential == null)
                                R.string.connection_not_configured else R.string.token_management_saved)
                        },
                    icon = Icons.Outlined.Key,
                    onClick = onManageTokens,
                )
                SettingToggleRow(
                    label = stringResource(R.string.codex_usage_auto_refresh_label),
                    description = stringResource(R.string.codex_usage_auto_refresh_description),
                    checked = codexUsageAutoRefresh,
                    onCheckedChange = onCodexUsageAutoRefreshChange,
                )
                Text(stringResource(R.string.antigravity_connection_description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UsageHistorySettings()
            }
                            }
                            SettingsSection.Appearance -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.appearance_title))
                Text(
                    stringResource(R.string.appearance_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 2 else 3,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeModeOption(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.theme_mode_system),
                        icon = Icons.Outlined.PhoneAndroid,
                        selected = settings.themeMode == ThemeMode.System,
                        description = stringResource(R.string.theme_mode_system_description),
                        onClick = { onThemeModeChange(ThemeMode.System) },
                    )
                    ThemeModeOption(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.theme_mode_light),
                        icon = Icons.Outlined.LightMode,
                        selected = settings.themeMode == ThemeMode.Light,
                        description = stringResource(R.string.theme_mode_light_description),
                        onClick = { onThemeModeChange(ThemeMode.Light) },
                    )
                    ThemeModeOption(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.theme_mode_dark),
                        icon = Icons.Outlined.DarkMode,
                        selected = settings.themeMode == ThemeMode.Dark,
                        description = stringResource(R.string.theme_mode_dark_description),
                        onClick = { onThemeModeChange(ThemeMode.Dark) },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPalette.entries.forEach { palette ->
                        val enabled = palette != ColorPalette.System || systemPaletteAvailable
                        PaletteOption(
                            palette = palette,
                            selected = settings.colorPalette == palette,
                            enabled = enabled,
                            supporting = when {
                                palette == ColorPalette.System && !systemPaletteAvailable ->
                                    stringResource(R.string.palette_dynamic_need_android12)
                                palette == ColorPalette.System ->
                                    stringResource(R.string.palette_dynamic_follow_wallpaper)
                                else -> null
                            },
                            onClick = {
                                if (enabled) onPaletteChange(palette)
                            },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.tab_order_section_title))
                Text(
                    stringResource(R.string.tab_order_section_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TabOrderEditor(
                    order = tabOrder,
                    onMove = { index, delta ->
                        onTabOrderChange(DashboardTab.move(tabOrder, index, delta))
                    },
                )
                val isDefault = tabOrder == DashboardTab.DEFAULT_ORDER
                TextButton(
                    onClick = onTabOrderReset,
                    enabled = !isDefault,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.tab_order_reset))
                }
            }
                            }
                            SettingsSection.Notifications -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.notification_section_title))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingToggleRow(
                        label = stringResource(R.string.notification_cat_language_label),
                        description = stringResource(R.string.notification_cat_language_description),
                        checked = notificationSettings.catLanguageEnabled,
                        onCheckedChange = onCatLanguageToggle,
                    )
                    val copy = NotificationCopy(notificationSettings.catLanguageEnabled)
                    val examples = IslandMonitorState.entries.map { stringResource(copy.resource(it.labelRes)) }
                    Text(
                        stringResource(R.string.notification_copy_preview, examples.joinToString(" / ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.notification_multi_preview,
                            stringResource(copy.multiChip(IslandMonitorState.ServiceIssue)) + "+2"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingToggleRow(
                        label = stringResource(R.string.notification_live_updates_label),
                        description = stringResource(R.string.notification_live_updates_description),
                        checked = notificationSettings.liveUpdatesEnabled,
                        onCheckedChange = onLiveUpdatesToggle,
                    )
                    SettingToggleRow(
                        label = stringResource(R.string.notification_threshold_reminders_label),
                        description = stringResource(R.string.notification_threshold_reminders_description),
                        checked = notificationSettings.thresholdRemindersEnabled,
                        onCheckedChange = onThresholdRemindersToggle,
                    )
                    SettingToggleRow(
                        label = stringResource(R.string.notification_reset_reminders_label),
                        description = stringResource(R.string.notification_reset_reminders_description),
                        checked = notificationSettings.codexResetRemindersEnabled,
                        onCheckedChange = onCodexResetRemindersToggle,
                    )
                }
            }
                        NotificationSystemSettings(notificationSettings)
                            }
                            SettingsSection.SystemEntries -> {
                        SystemEntriesContent()
                            }
                            SettingsSection.General -> {
                        SettingToggleRow(stringResource(R.string.haptic_feedback_title), stringResource(R.string.haptic_feedback_description),
                            hapticFeedbackEnabled, onHapticFeedbackChange, provideFeedback = false)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.language_section_title))
                Text(
                    stringResource(R.string.language_section_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppLanguage.entries.forEach { option ->
                    LanguageOption(
                        option = option,
                        selected = language == option,
                        onClick = { onLanguageChange(option) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.timezone_section_title))
                Text(
                    stringResource(R.string.timezone_section_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val previewZone = DisplayTimeZones.resolve(timeZoneId)
                Text(
                    stringResource(
                        R.string.timezone_preview,
                        DisplayTime.formatDateTime(Instant.now(), previewZone, withYear = true),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                TimeZonePicker(
                    timeZoneId = timeZoneId,
                    onTimeZoneChange = onTimeZoneChange,
                )
            }
                            }
                        }
                    }
            }
            AdaptivePaneLayout(modifier = Modifier.padding(padding), allowPair = wide,
                minPaneWidthDp = 240f, preferredPrimaryWidthDp = 280f, maxContentWidthDp = 1440f,
                single = { if (explicitCurrent == null) categories() else detail() },
                primary = categories, secondary = detail)
        }
    }
}

@Composable
private fun TabOrderEditor(
    order: List<DashboardTab>,
    onMove: (index: Int, delta: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { index, tab ->
            TabOrderRow(
                position = index + 1,
                tab = tab,
                canMoveUp = index > 0,
                canMoveDown = index < order.lastIndex,
                onMoveUp = { onMove(index, -1) },
                onMoveDown = { onMove(index, 1) },
            )
        }
    }
}

@Composable
private fun TabOrderRow(
    position: Int,
    tab: DashboardTab,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val tabLabel = stringResource(tab.labelRes)
    val moveUpDescription = stringResource(R.string.tab_order_move_up, tabLabel)
    val moveDownDescription = stringResource(R.string.tab_order_move_down, tabLabel)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(tabLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                DashboardTabScopeBadge(tab.scope)
            }
            Row(Modifier.align(Alignment.End)) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, moveUpDescription) }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, moveDownDescription) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HorizontalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun SettingNavRow(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PercentDisplayModeSelector(
    selected: PercentDisplayMode,
    onSelected: (PercentDisplayMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PercentDisplayMode.entries.forEach { mode ->
            val label = when (mode) {
                PercentDisplayMode.Used -> stringResource(R.string.notification_display_mode_used)
                PercentDisplayMode.Remaining ->
                    stringResource(R.string.notification_display_mode_remaining)
            }
            PercentModeOption(
                modifier = Modifier.weight(1f),
                label = label,
                selected = selected == mode,
                onClick = { onSelected(mode) },
            )
        }
    }
}

@Composable
private fun PercentModeOption(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val feedback = LocalAppFeedback.current
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        modifier = modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable { if (!selected) { feedback.selection(); onClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = border,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
internal fun SettingToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    provideFeedback: Boolean = true,
) {
    val feedback = LocalAppFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = { value ->
                if (value != checked) { onCheckedChange(value); if (provideFeedback) feedback.toggle(value) }
            })
        }
    }
}

@Composable
private fun TimeZonePicker(
    timeZoneId: String,
    onTimeZoneChange: (String) -> Unit,
) {
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val selectedOption = DisplayTimeZones.option(timeZoneId)
    val matches = remember(query, timeZoneId, resources) {
        DisplayTimeZones.search(query) { option ->
            resources.getString(option.labelRes)
        }.filter { it.id != timeZoneId }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeZoneOption(
            id = DisplayTimeZones.SYSTEM_ID,
            label = stringResource(R.string.timezone_system),
            selected = timeZoneId == DisplayTimeZones.SYSTEM_ID,
            onClick = {
                query = ""
                focusManager.clearFocus()
                onTimeZoneChange(DisplayTimeZones.SYSTEM_ID)
            },
        )
        if (timeZoneId != DisplayTimeZones.SYSTEM_ID) {
            TimeZoneOption(
                id = timeZoneId,
                label = selectedOption?.let { stringResource(it.labelRes) }
                    ?: DisplayTimeZones.label(timeZoneId, resources),
                selected = true,
                onClick = { query = "" },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.timezone_search_label)) },
            placeholder = { Text(stringResource(R.string.timezone_search_placeholder)) },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.timezone_search_clear),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        if (query.trim().isNotEmpty() && matches.isEmpty()) {
            Text(
                stringResource(R.string.timezone_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        matches.forEach { option ->
            TimeZoneOption(
                id = option.id,
                label = stringResource(option.labelRes),
                selected = false,
                onClick = {
                    onTimeZoneChange(option.id)
                    query = ""
                    focusManager.clearFocus()
                },
            )
        }
    }
}

@Composable
private fun TimeZoneOption(
    id: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val feedback = LocalAppFeedback.current
    val zone = DisplayTimeZones.resolve(id)
    val offset = DisplayTime.offsetLabel(zone)
    val supporting = if (id == DisplayTimeZones.SYSTEM_ID) {
        stringResource(R.string.timezone_device_offset, offset)
    } else {
        offset
    }
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "$label, $supporting"
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable { if (!selected) { feedback.selection(); onClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    stringResource(R.string.selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LanguageOption(
    option: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val feedback = LocalAppFeedback.current
    val label = stringResource(option.labelRes)
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable { if (!selected) { feedback.selection(); onClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected) {
                Text(
                    stringResource(R.string.selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ThemeModeOption(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val feedback = LocalAppFeedback.current
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        modifier = modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "$label, $description"
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable { if (!selected) { feedback.selection(); onClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = border,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun PaletteOption(
    palette: ColorPalette,
    selected: Boolean,
    enabled: Boolean,
    supporting: String?,
    onClick: () -> Unit,
) {
    val feedback = LocalAppFeedback.current
    val paletteName = stringResource(palette.displayNameRes)
    val unavailable = stringResource(R.string.palette_unavailable)
    val optionDescription = stringResource(R.string.palette_option_description, paletteName)
    val paletteDescription = buildString {
        append(optionDescription)
        if (supporting != null) append(" · $supporting")
        if (!enabled) append(" · $unavailable")
    }
    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = paletteDescription
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { if (!selected) { feedback.selection(); onClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = border,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(palette.previewSwatch.copy(alpha = if (enabled) 1f else 0.4f))
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    paletteName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                supporting?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.6f,
                        ),
                    )
                }
            }
            if (selected) {
                Text(
                    stringResource(R.string.selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
