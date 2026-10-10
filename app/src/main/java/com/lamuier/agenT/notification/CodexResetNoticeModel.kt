package com.lamuier.agenT.notification

import com.lamuier.agenT.codex.*

internal object CodexResetNoticeModel {
    fun newlyConfirmed(before: CodexResetLedger, after: CodexResetLedger, now: Long): List<CodexResetEvent> {
        val known = before.events.map { it.id }.toSet()
        return after.events.filter { it.id !in known && it.source == CodexResetSource.Observed &&
            it.kind in listOf(CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly) &&
            it.at in (now - 86400)..now }
    }
    fun source(event: CodexResetEvent) = when (event.kind) {
        CodexResetKind.AutomaticPrimary -> QuotaNoticeSource.CodexPrimary
        CodexResetKind.AutomaticWeekly -> QuotaNoticeSource.CodexWeekly
        else -> error("Not a confirmed window reset")
    }
}
