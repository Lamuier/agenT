package com.lamuier.agenT.model

import androidx.annotation.StringRes
import com.lamuier.agenT.R

/**
 * 主界面功能页签。顺序可在设置中自定义；存储用稳定 [id]，缺项按默认顺序补齐。
 */
enum class DashboardTabScope(@StringRes val labelRes: Int) {
    Common(R.string.tab_scope_common),
    Cursor(R.string.tab_scope_cursor),
    Codex(R.string.tab_scope_codex),
}

enum class DashboardTab(val id: String, @StringRes val labelRes: Int, val scope: DashboardTabScope) {
    Overview("overview", R.string.tab_overview, DashboardTabScope.Common),
    Usage("usage", R.string.tab_usage, DashboardTabScope.Common),
    Tasks("tasks", R.string.tab_tasks, DashboardTabScope.Cursor),
    Resets("resets", R.string.tab_resets, DashboardTabScope.Codex),
    Status("status", R.string.tab_status, DashboardTabScope.Common);

    companion object {
        val DEFAULT_ORDER: List<DashboardTab> = listOf(Overview, Usage, Tasks, Resets, Status)

        fun fromId(id: String): DashboardTab? =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

        /**
         * 小组件点击打开应用时解析目标页签：优先 extra 里的页签 id，
         * 否则按小组件种类回退（状态规格 → 状态，用量迷你/详情 → 概览）。
         */
        fun fromWidgetLaunch(tabId: String?, widgetKind: String?): DashboardTab? {
            // Old pending intents may still point at the removed Billing page.
            // Keep this alias out of fromId so saved tab orders simply drop Billing.
            if (tabId?.trim()?.equals("billing", ignoreCase = true) == true) return Usage
            fromId(tabId.orEmpty())?.let { return it }
            val kind = widgetKind?.trim()?.lowercase().orEmpty()
            return when {
                kind.startsWith("status") -> Status
                kind == "mini" || kind == "tall" -> Overview
                else -> null
            }
        }

        /** 解析已存顺序：忽略未知 id、去重，并按默认顺序补上缺失页签。 */
        fun resolveOrder(storedIds: List<String>?): List<DashboardTab> {
            val seen = LinkedHashSet<DashboardTab>()
            storedIds.orEmpty().forEach { token ->
                fromId(token)?.takeIf { it in DEFAULT_ORDER }?.let { seen += it }
            }
            DEFAULT_ORDER.forEach { seen += it }
            return seen.toList()
        }

        fun resolveOrder(raw: String?): List<DashboardTab> = resolveOrder(
            raw?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() },
        )

        fun serialize(order: List<DashboardTab>): String =
            resolveOrder(order.map { it.id }).joinToString(",") { it.id }

        /** Add Tasks to legacy four-tab orders; drop Billing without moving surviving tabs. */
        fun resolveSavedOrder(raw: String?): List<DashboardTab> {
            val stored = raw?.split(',').orEmpty().mapNotNull(::fromId)
            val order = resolveOrder(raw)
            if (Usage !in stored || Tasks in stored) return order
            val upgraded = order.filterNot { it == Tasks }.toMutableList()
            upgraded.add(upgraded.indexOf(Usage) + 1, Tasks)
            return upgraded
        }

        fun move(order: List<DashboardTab>, index: Int, delta: Int): List<DashboardTab> {
            val items = resolveOrder(order.map { it.id }).toMutableList()
            val target = index + delta
            if (index !in items.indices || target !in items.indices) return items
            val item = items.removeAt(index)
            items.add(target, item)
            return items
        }
    }
}
