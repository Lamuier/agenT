package com.lamuier.agenT.widget

import com.lamuier.agenT.model.DashboardTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot, non-secret destination; consumed only when the destination page is ready. */
internal object WidgetNavigation {
    data class Destination(val source: String, val tab: DashboardTab, val serial: Long)
    private val pending = MutableStateFlow<Destination?>(null)
    val destination = pending.asStateFlow()
    private var serial = 0L
    @Synchronized fun open(source: String?, tab: DashboardTab?) {
        pending.value = if (source in WidgetSourcePolicy.order && tab in listOf(DashboardTab.Usage, DashboardTab.Status))
            Destination(requireNotNull(source), requireNotNull(tab), ++serial) else null
    }
    fun consume(value: Destination) { pending.compareAndSet(value, null) }
}
