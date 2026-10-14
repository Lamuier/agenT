package com.lamuier.agenT.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver

/** Owned by the page, so changes in lazy items and pane arrangements cannot discard a selection. */
@Stable
internal class UsagePageFilters(initialMetric: String = "cost") {
    var rangeMode by mutableIntStateOf(0)
    var monthOffset by mutableIntStateOf(0)
    var cycleOffset by mutableIntStateOf(-1)
    var compareExpanded by mutableStateOf(false)
    var sortTokens by mutableStateOf(false)
    var allModels by mutableStateOf(false)
    var days by mutableIntStateOf(7)
    var metric by mutableStateOf(initialMetric)
    var selectedAt by mutableStateOf<Long?>(null)
    var expandedModels by mutableStateOf(setOf<String>())
    companion object {
        val Saver = listSaver<UsagePageFilters, Any>(
            save = { listOf(it.rangeMode, it.monthOffset, it.cycleOffset, it.compareExpanded, it.sortTokens, it.allModels, it.days, it.metric, it.selectedAt ?: -1L) + it.expandedModels.sorted() },
            restore = { values -> UsagePageFilters(values[7] as String).apply {
                rangeMode = values[0] as Int; monthOffset = values[1] as Int; cycleOffset = values[2] as Int
                compareExpanded = values[3] as Boolean; sortTokens = values[4] as Boolean; allModels = values[5] as Boolean
                days = values[6] as Int; selectedAt = (values[8] as Long).takeIf { it >= 0 }; expandedModels = values.drop(9).map { it as String }.toSet()
            } },
        )
    }
}
