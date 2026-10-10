package com.lamuier.agenT.notification

import androidx.annotation.StringRes
import com.lamuier.agenT.R
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator

internal enum class PendingServiceReason(@StringRes val messageRes: Int) {
    Missing(R.string.notification_service_missing),
    Expired(R.string.notification_service_expired),
    Unknown(R.string.notification_service_unknown),
    Incomplete(R.string.notification_service_incomplete),
}

/** One row per logical quota or service condition, not one row per state category. */
internal sealed interface MonitorAttention {
    val key: String
    val state: IslandMonitorState
    val priority: Int

    data class Quota(val metric: QuotaNoticeMetric) : MonitorAttention {
        override val key = "quota:${metric.source.name}"
        override val state = if (metric.usedPercent >= 100) IslandMonitorState.EmptyQuota else IslandMonitorState.LowQuota
        override val priority = if (state == IslandMonitorState.EmptyQuota) 600 else 300
    }

    data class Service(val provider: String, val indicator: StatusIndicator) : MonitorAttention {
        override val key = "service:$provider"
        override val state = if (indicator == StatusIndicator.Maintenance) IslandMonitorState.Maintenance else IslandMonitorState.ServiceIssue
        override val priority = when (indicator) {
            StatusIndicator.Critical -> 800
            StatusIndicator.Major -> 700
            StatusIndicator.Minor -> 500
            StatusIndicator.Maintenance -> 400
            else -> 0
        }
    }

    data class PendingQuota(val source: QuotaNoticeSource) : MonitorAttention {
        override val key = "quota-sync:${source.name}"
        override val state = IslandMonitorState.Syncing
        override val priority = 100
    }

    data class PendingService(val provider: String, val reason: PendingServiceReason) : MonitorAttention {
        override val key = "service-sync:$provider"
        override val state = IslandMonitorState.Syncing
        override val priority = 100
    }
}

internal data class MonitorSnapshot(val primary: IslandMonitorState, val attentions: List<MonitorAttention>) {
    val additionalCount: Int get() = (attentions.size - 1).coerceAtLeast(0)
    val hasMultiple: Boolean get() = additionalCount > 0

    companion object {
        fun collect(
            metrics: List<QuotaNoticeMetric>,
            freshSources: Set<QuotaNoticeSource>,
            services: Map<String, ServiceStatus?>,
        ): MonitorSnapshot {
            val quotas = metrics.distinctBy { it.source }
            val expectedProviders = quotas.map { if (it.source == QuotaNoticeSource.Cursor) "Cursor" else "Codex" }.toSet()
            val providers = services.keys + expectedProviders
            val entries = buildList {
                providers.forEach { provider ->
                    val status = services[provider]
                    if (status == null) {
                        if (provider in expectedProviders) add(MonitorAttention.PendingService(provider, PendingServiceReason.Missing))
                        return@forEach
                    }
                    if (status.cacheAgeSeconds > IslandMonitorState.FRESH_SECONDS) {
                        add(MonitorAttention.PendingService(provider, PendingServiceReason.Expired))
                        return@forEach
                    }
                    if (status.indicator in setOf(StatusIndicator.Critical, StatusIndicator.Major,
                            StatusIndicator.Minor, StatusIndicator.Maintenance)) {
                        add(MonitorAttention.Service(provider, status.indicator))
                    }
                    if (status.indicator == StatusIndicator.Unknown) {
                        add(MonitorAttention.PendingService(provider, PendingServiceReason.Unknown))
                    } else if (status.partialComponents) {
                        add(MonitorAttention.PendingService(provider, PendingServiceReason.Incomplete))
                    }
                }
                quotas.forEach { metric ->
                    if (metric.source !in freshSources || !metric.usedPercent.isFinite()) {
                        add(MonitorAttention.PendingQuota(metric.source))
                    } else if (metric.usedPercent >= 80) {
                        add(MonitorAttention.Quota(metric))
                    }
                }
            }.distinctBy { it.key }.sortedWith(compareByDescending<MonitorAttention> { it.priority }
                .thenByDescending { (it as? MonitorAttention.Quota)?.metric?.usedPercent ?: 0.0 })
            val primary = entries.firstOrNull()?.state
                ?: if (quotas.isEmpty()) IslandMonitorState.Syncing else IslandMonitorState.Running
            return MonitorSnapshot(primary, entries)
        }
    }
}
