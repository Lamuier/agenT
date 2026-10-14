package com.lamuier.agenT.notification

import com.lamuier.agenT.model.AgentTaskStatus
import com.lamuier.agenT.model.AgenTasks

/** Only observed transitions are recent activity; a first sync must not replay task history. */
internal object TaskActivityModel {
    const val COMPLETION_RETENTION_MILLIS = 15L * 60 * 1000

    fun isActive(status: AgentTaskStatus) = status == AgentTaskStatus.Creating || status == AgentTaskStatus.Running
    fun isTerminal(status: AgentTaskStatus) = status in setOf(AgentTaskStatus.Finished, AgentTaskStatus.Error, AgentTaskStatus.Expired)

    fun recentCompletions(tasks: AgenTasks, nowMillis: Long): Map<String, Long> {
        val terminalIds = tasks.tasks.distinctBy { it.id }.filter { isTerminal(it.status) }.map { it.id }.toSet()
        return tasks.completedAtMillis.filter { (id, time) ->
            id in terminalIds && nowMillis - time in 0 until COMPLETION_RETENTION_MILLIS
        }
    }

    /** A stale button must never acknowledge a newer run of the same task. */
    fun acknowledge(tasks: AgenTasks, displayedCompletions: Map<String, Long>): AgenTasks = tasks.copy(
        completedAtMillis = tasks.completedAtMillis.filterNot { (id, time) -> displayedCompletions[id] == time },
    )

    fun nextExpiry(tasks: AgenTasks?, nowMillis: Long): Long? = tasks?.let {
        recentCompletions(it, nowMillis).values.minOrNull()?.plus(COMPLETION_RETENTION_MILLIS)
    }

    fun observe(previous: AgenTasks?, current: AgenTasks, nowMillis: Long): AgenTasks {
        val baseline = previous?.takeIf { it.accountId == current.accountId && it.observedAtMillis > 0 }
        val oldTasks = baseline?.tasks?.associateBy { it.id }.orEmpty()
        val completed = current.tasks.distinctBy { it.id }.mapNotNull { task ->
            if (!isTerminal(task.status)) return@mapNotNull null
            val old = oldTasks[task.id]
            val retained = baseline?.completedAtMillis?.get(task.id)?.takeIf {
                old?.status == task.status && nowMillis - it in 0 until COMPLETION_RETENTION_MILLIS
            }
            val ended = old?.let { isActive(it.status) } == true
            // A task can start and finish between polls, but older newly-listed history is not an event.
            val betweenPolls = baseline != null && old == null &&
                task.createdAtMs > baseline.observedAtMillis && task.createdAtMs <= nowMillis
            val time = retained ?: nowMillis.takeIf { ended || betweenPolls } ?: return@mapNotNull null
            task.id to time
        }.toMap()
        return current.copy(observedAtMillis = nowMillis, completedAtMillis = completed)
    }
}
