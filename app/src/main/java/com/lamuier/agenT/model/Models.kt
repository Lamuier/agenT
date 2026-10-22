package com.lamuier.agenT.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lamuier.agenT.R

@Immutable
data class CursorAccount(
    val id: Int,
    val alias: String,
    val tokenExpired: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Immutable
data class AgenTOverview(
    val accountId: Int,
    val alias: String,
    val isTeam: Boolean,
    val plan: PlanInfo,
    val billingCycle: BillingCycle,
    val usage: UsageMetrics,
    val credits: Credits,
    val onDemand: OnDemandUsage?,
    val subscription: Subscription,
    /** 本计费周期按模型汇总的 Token；接口失败或旧缓存缺失时为 null。 */
    val tokenUsage: TokenUsageBreakdown? = null,
    /** Grok Bot 每周独立额度；无开通、企业共享池或接口失败时为 null。 */
    val grokBot: GrokBotUsage? = null,
    /** 自然月 / 历史计费周期的 Token 汇总；接口失败时窗口仍在、明细为 null。 */
    val history: UsageHistory? = null,
    val fetchedAt: String,
    val fromCache: Boolean,
    val cacheAgeSeconds: Int,
    val isLocalCache: Boolean = false,
    val partialData: Boolean = false,
)

/** 本周期 Token 汇总（来自 DashboardService/GetAggregatedUsageEvents）。 */
@Immutable
data class TokenUsageBreakdown(
    val models: List<ModelTokenUsage>,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheWriteTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCostDollars: Double,
    val availableMetrics: Set<String> = setOf("input", "output", "read", "write", "cost"),
    val modelsAvailable: Boolean = true,
)

@Immutable
data class ModelTokenUsage(
    val modelIntent: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val costDollars: Double,
    val tier: Int?,
    val availableMetrics: Set<String> = setOf("input", "output", "read", "write", "cost"),
)

@Immutable
data class PlanInfo(
    val name: String?,
    val price: String?,
    val includedAmountDollars: Double,
    val billingCycleEnd: String?,
)

@Immutable
data class BillingCycle(
    val start: String?,
    val end: String?,
)

@Immutable
data class UsageMetrics(
    val totalUsed: Double,
    val totalFormat: TotalFormat,
    val totalSpendDollars: Double,
    val includedSpendDollars: Double,
    val bonusSpendDollars: Double,
    val limitDollars: Double,
    val remainingDollars: Double,
    val autoPercentUsed: Double?,
    val apiPercentUsed: Double?,
    val displayMessage: String?,
    val remainingBonus: Boolean,
    val availableMetrics: Set<String> = setOf("total", "included", "bonus", "remaining", "limit"),
)

enum class TotalFormat {
    Percent,
    Dollars,
}

@Immutable
data class Credits(
    val totalDollars: Double,
    val grantTotalDollars: Double,
    val stripeBalanceDollars: Double,
)

@Immutable
data class GrokBotUsage(
    val percentUsed: Double,
    val periodStart: String?,
    val resetsAt: String?,
)

@Immutable
data class UsageHistory(
    val previousCycle: UsageWindow?,
    val calendarMonth: UsageWindow?,
)

@Immutable
data class UsageWindow(
    val start: String?,
    val end: String?,
    val yearMonth: String? = null,
    val tokenUsage: TokenUsageBreakdown?,
)

@Immutable
data class OnDemandUsage(
    val limitType: String?,
    val totalSpendDollars: Double,
    val individualLimitDollars: Double,
    val individualUsedDollars: Double,
    val individualRemainingDollars: Double,
    val pooledLimitDollars: Double,
    val pooledUsedDollars: Double,
    val pooledRemainingDollars: Double,
    val availableMetrics: Set<String> = setOf("total", "individualRemaining", "pooledRemaining"),
)

@Immutable
data class Subscription(
    val membershipType: String?,
    val status: String?,
)

enum class AppStage {
    Booting,
    Dashboard,
}

/** 云端任务（Cursor 后台智能体）的执行状态。 */
enum class AgentTaskStatus {
    Creating,
    Running,
    Finished,
    Error,
    Expired,
    Unknown,
}

/** 云端任务关联的 Pull Request 状态。 */
enum class AgentTaskPrStatus {
    Open,
    Draft,
    Merged,
    Closed,
    Unknown,
}

/**
 * 云端任务的发起来源。对应 `BACKGROUND_COMPOSER_SOURCE_*`；
 * 官网默认会隐藏 API / SDK / Grok Bot / Remote Control 等来源，本应用按来源分组展示。
 */
enum class AgentTaskSource {
    Website,
    Editor,
    Slack,
    Linear,
    Ios,
    Api,
    GitHub,
    Cli,
    GitHubCi,
    GitLab,
    EnvSetup,
    Grind,
    Bugbot,
    Automations,
    Sdk,
    GrokBot,
    RemoteControl,
    Unknown,
}

/** 任务页列表分组方式，保存在本机。 */
enum class TaskGroupMode(val storageKey: String, @StringRes val labelRes: Int) {
    Repository("repository", R.string.task_group_repository),
    Status("status", R.string.task_group_status),
    Recency("recency", R.string.task_group_recency),
    Source("source", R.string.task_group_source);

    companion object {
        fun fromStorage(value: String?): TaskGroupMode =
            entries.firstOrNull { it.storageKey.equals(value?.trim(), ignoreCase = true) }
                ?: Repository
    }
}

/** 4×3 服务状态小组件的版式，由用户在系统入口设置中选择。 */
enum class StatusWidgetStyle(val storageKey: String, @StringRes val labelRes: Int, @StringRes val summaryRes: Int) {
    Summary("summary", R.string.status_widget_style_summary, R.string.status_widget_style_summary_hint),
    Signals("signals", R.string.status_widget_style_signals, R.string.status_widget_style_signals_hint),
    Alerts("alerts", R.string.status_widget_style_alerts, R.string.status_widget_style_alerts_hint);

    companion object {
        fun fromStorage(value: String?): StatusWidgetStyle =
            entries.firstOrNull { it.storageKey.equals(value?.trim(), ignoreCase = true) } ?: Summary
    }
}

@Immutable
data class AgentTask(
    val id: String,
    val name: String,
    val status: AgentTaskStatus,
    val repoUrl: String?,
    val branchName: String?,
    val prUrl: String?,
    val prStatus: AgentTaskPrStatus?,
    val linesAdded: Int,
    val linesRemoved: Int,
    val filesChanged: Int,
    val modelName: String?,
    val maxMode: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastActivityMs: Long?,
    val source: AgentTaskSource = AgentTaskSource.Unknown,
) {
    /** 列表排序与相对时间展示共用：取最近一次活动 / 更新 / 创建时间。 */
    val latestTimeMs: Long
        get() = maxOf(lastActivityMs ?: 0L, updatedAtMs, createdAtMs)
}

@Immutable
data class AgenTasks(
    val accountId: Int,
    val tasks: List<AgentTask>,
    val fetchedAt: String,
    val fromCache: Boolean,
    val cacheAgeSeconds: Int = 0,
    /** Local observation time and terminal transitions, persisted with the encrypted task cache. */
    val observedAtMillis: Long = 0,
    val completedAtMillis: Map<String, Long> = emptyMap(),
)

/** 长按图标静态 Shortcut 对应的应用内动作。 */
enum class ShortcutAction(val intentAction: String) {
    ManageTokens(INTENT_ACTION_PREFIX + ".SHORTCUT_MANAGE_TOKENS"),
    Settings(INTENT_ACTION_PREFIX + ".SHORTCUT_SETTINGS"),
    Overview(INTENT_ACTION_PREFIX + ".SHORTCUT_OVERVIEW"),
    Tasks(INTENT_ACTION_PREFIX + ".SHORTCUT_TASKS");

    companion object {
        fun fromIntentAction(action: String?): ShortcutAction? = when (action) {
            INTENT_ACTION_PREFIX + ".SHORTCUT_REVEAL_TOKEN",
            INTENT_ACTION_PREFIX + ".SHORTCUT_MANAGE_ACCOUNTS" -> ManageTokens
            else -> entries.firstOrNull { it.intentAction == action }
        }
    }
}

private const val INTENT_ACTION_PREFIX = "com.lamuier.agenT.action"

@Immutable
data class AppUiState(
    val stage: AppStage = AppStage.Booting,
    val accounts: List<CursorAccount> = emptyList(),
    val selectedAccountId: Int? = null,
    val usage: AgenTOverview? = null,
    val tasks: AgenTasks? = null,
    val serviceStatus: ServiceStatus? = null,
    val loadingAccounts: Boolean = false,
    val loadingUsage: Boolean = false,
    val loadingTasks: Boolean = false,
    val loadingStatus: Boolean = false,
    val refreshing: Boolean = false,
    val refreshingTasks: Boolean = false,
    val refreshingStatus: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val tasksError: String? = null,
    val statusError: String? = null,
    val usageHistory: List<com.lamuier.agenT.usage.UsageObservation> = emptyList(),
    val usageHistoryError: Boolean = false,
    val extraHistory: Map<String, UsageWindow> = emptyMap(),
    val loadingHistoryKeys: Set<String> = emptySet(),
    val historyErrors: Map<String, String> = emptyMap(),
    val loadingHistoryKey: String? = null,
    val historyError: String? = null,
    val historyErrorKey: String? = null,
)

/** 官方状态页的整体指示灯。 */
enum class StatusIndicator {
    Unknown,
    None,
    Minor,
    Major,
    Critical,
    Maintenance,
}

/** 单个服务组件的可用状态。 */
enum class ComponentStatus {
    Operational,
    DegradedPerformance,
    PartialOutage,
    MajorOutage,
    UnderMaintenance,
    Unknown,
}

@Immutable
data class ServiceStatus(
    val description: String,
    val indicator: StatusIndicator,
    val pageUpdatedAt: String?,
    val pageUrl: String,
    val components: List<StatusComponent>,
    val activeIncidents: List<StatusIncident>,
    val scheduledMaintenances: List<StatusIncident>,
    val recentIncidents: List<StatusIncident>,
    val fetchedAt: String,
    val fromCache: Boolean,
    val cacheAgeSeconds: Int = 0,
    val partialHistory: Boolean = false,
    val partialComponents: Boolean = false,
)

@Immutable
data class StatusComponent(
    val id: String,
    val name: String,
    val status: ComponentStatus,
    val position: Int,
)

@Immutable
data class StatusIncident(
    val id: String,
    val name: String,
    val status: String,
    val impact: String,
    val createdAt: String?,
    val updatedAt: String?,
    val resolvedAt: String?,
    val scheduledFor: String?,
    val scheduledUntil: String?,
    val shortlink: String?,
    val affectedComponents: List<String>,
    val updates: List<StatusIncidentUpdate>,
)

@Immutable
data class StatusIncidentUpdate(
    val status: String,
    val body: String,
    val displayAt: String?,
)
