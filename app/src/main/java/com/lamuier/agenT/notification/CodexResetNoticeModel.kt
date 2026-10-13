package com.lamuier.agenT.notification

import com.lamuier.agenT.codex.*

internal object CodexResetNoticeModel {
    fun newlyConfirmed(before: CodexResetLedger, after: CodexResetLedger, now: Long): List<CodexResetEvent> {
        val prior = before.previous ?: return emptyList()
        val next = after.previous ?: return emptyList()
        if (next.at <= prior.at || next.at > now) return emptyList()
        fun confirmed(kind: CodexResetKind, old: CodexQuotaWindow?, fresh: CodexQuotaWindow?): CodexResetEvent? {
            val deadline = old?.resetAtEpochSeconds ?: return null
            val updated = fresh?.resetAtEpochSeconds ?: return null
            if (old.limitWindowSeconds != fresh.limitWindowSeconds || updated <= deadline + 60 ||
                deadline !in (now - 86400)..next.at) return null
            return CodexResetEvent(CodexResetIdentity.hash("$kind|$deadline|$updated"), kind,
                CodexResetSource.Observed, deadline, before = old.usedPercent, after = fresh.usedPercent)
        }
        return listOfNotNull(confirmed(CodexResetKind.AutomaticPrimary, prior.primary, next.primary),
            confirmed(CodexResetKind.AutomaticWeekly, prior.weekly, next.weekly))
    }
    fun source(event: CodexResetEvent) = when (event.kind) {
        CodexResetKind.AutomaticPrimary -> QuotaNoticeSource.CodexPrimary
        CodexResetKind.AutomaticWeekly -> QuotaNoticeSource.CodexWeekly
        else -> error("Not a confirmed window reset")
    }
}
