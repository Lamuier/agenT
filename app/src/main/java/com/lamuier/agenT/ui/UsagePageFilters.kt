package com.lamuier.agenT.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import com.lamuier.agenT.R

/** Ordinal is stored by the usage-page saver. Append metrics; do not reorder them. */
internal enum class UsageModelMetric(@StringRes val labelRes: Int) {
    Cost(R.string.u_sort_cost),
    InputOutput(R.string.u_sort_tokens),
    AllTokens(R.string.u_sort_all_tokens),
}

/** Owned by the page, so changes in lazy items and pane arrangements cannot discard a selection. */
@Stable
internal class UsagePageFilters(initialMetric: String = "cost") {
    var rangeMode by mutableIntStateOf(0)
    var monthOffset by mutableIntStateOf(0)
    var cycleOffset by mutableIntStateOf(-1)
    var compareExpanded by mutableStateOf(false)
    var modelMetric by mutableStateOf(UsageModelMetric.Cost)
    var allModels by mutableStateOf(false)
    var days by mutableIntStateOf(7)
    var metric by mutableStateOf(initialMetric)
    var selectedAt by mutableStateOf<Long?>(null)
    var expandedModels by mutableStateOf(setOf<String>())
    companion object {
        val Saver = listSaver<UsagePageFilters, Any>(
            save = { listOf(it.rangeMode, it.monthOffset, it.cycleOffset, it.compareExpanded, it.modelMetric.ordinal, it.allModels, it.days, it.metric, it.selectedAt ?: -1L) + it.expandedModels.sorted() },
            restore = { values -> UsagePageFilters(values[7] as String).apply {
                rangeMode = values[0] as Int; monthOffset = values[1] as Int; cycleOffset = values[2] as Int
                compareExpanded = values[3] as Boolean
                modelMetric = when (val saved = values[4]) {
                    is Boolean -> if (saved) UsageModelMetric.InputOutput else UsageModelMetric.Cost
                    is Int -> UsageModelMetric.entries.getOrElse(saved) { UsageModelMetric.Cost }
                    else -> UsageModelMetric.Cost
                }
                allModels = values[5] as Boolean
                days = values[6] as Int; selectedAt = (values[8] as Long).takeIf { it >= 0 }; expandedModels = values.drop(9).map { it as String }.toSet()
            } },
        )
    }
}
