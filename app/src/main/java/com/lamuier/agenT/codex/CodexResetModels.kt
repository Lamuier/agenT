package com.lamuier.agenT.codex

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import com.lamuier.agenT.util.TokenUtils
import java.security.MessageDigest
import org.json.JSONObject

@Immutable
data class CodexResetCredit(
    val id: String, val status: String, val grantedAt: Long?, val expiresAt: Long?,
    val title: String?, val redeemedAt: Long? = null,
)

@Immutable
data class CodexResetCredits(val availableCount: Int?, val details: List<CodexResetCredit>?)

enum class CodexResetKind { ManualUse, OfficialExtra, AutomaticPrimary, AutomaticWeekly, WindowChanged, BalanceChanged, CreditGranted, CreditExpired }
enum class CodexResetSource { Official, Observed, Registered }
enum class CodexResetPeriod { Week, Month, Subscription }

@Immutable
data class CodexResetEvent(
    val id: String, val kind: CodexResetKind, val source: CodexResetSource,
    val at: Long, val count: Int = 1, val note: String = "",
    val before: Double? = null, val after: Double? = null,
)

@Immutable
data class CodexResetObservation(
    val at: Long, val primary: CodexQuotaWindow?, val weekly: CodexQuotaWindow?,
    val available: Int?, val credits: List<CodexResetCredit> = emptyList(),
)

@Immutable
data class CodexResetLedger(
    val startedAt: Long = 0, val previous: CodexResetObservation? = null,
    val events: List<CodexResetEvent> = emptyList(), val subscriptionDay: Int? = null,
    val truncated: Boolean = false,
)

data class CodexResetRange(val start: Long, val end: Long)

internal object CodexResetPeriods {
    fun range(period: CodexResetPeriod, now: Instant, zone: ZoneId, anchor: Int?): CodexResetRange? {
        val today = now.atZone(zone).toLocalDate()
        val start: LocalDate
        val end: LocalDate
        when (period) {
            CodexResetPeriod.Week -> {
                start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                end = start.plusWeeks(1)
            }
            CodexResetPeriod.Month -> { start = today.withDayOfMonth(1); end = start.plusMonths(1) }
            CodexResetPeriod.Subscription -> {
                if (anchor == null || anchor !in 1..31) return null
                fun anchored(month: LocalDate) = month.withDayOfMonth(anchor.coerceAtMost(month.lengthOfMonth()))
                val current = anchored(today.withDayOfMonth(1))
                val month = if (today < current) today.withDayOfMonth(1).minusMonths(1) else today.withDayOfMonth(1)
                start = anchored(month)
                end = anchored(month.plusMonths(1))
            }
        }
        return CodexResetRange(start.atStartOfDay(zone).toEpochSecond(), end.atStartOfDay(zone).toEpochSecond())
    }
    fun events(ledger: CodexResetLedger, range: CodexResetRange?): List<CodexResetEvent> =
        if (range == null) emptyList() else ledger.events.filter { it.at >= range.start && it.at < range.end }
}

/** Stable per user/workspace when JWT identity is known; opaque tokens are isolated, never guessed to be the same account. */
internal object CodexResetIdentity {
    fun key(token: String, account: String): String {
        val claims = runCatching {
            val payload = TokenUtils.jwtPayload(token)
            if (payload.isEmpty()) return@runCatching null
            JSONObject(payload)
        }.getOrNull()
        fun safeIdentity(value: String?) = value?.takeIf { it.isNotBlank() && it != "null" && it.length <= 256 && it.none(Char::isISOControl) }
        val subject = safeIdentity(claims?.optString("sub"))
        val workspace = safeIdentity(account.trim()) ?: safeIdentity(claims?.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id"))
        val known = subject != null && workspace != null
        return hash(JSONObject().put("type", if (known) "user" else "opaque")
            .put("identity", if (known) subject else token).put("workspace", workspace ?: account).toString())
    }
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Keep only the latest fresh quota snapshot for reminders; never append local reset history. */
internal object CodexResetTracker {
    const val MAX_EVENTS = 500
    fun observe(ledger: CodexResetLedger, usage: CodexQuotaOverview, at: Long): CodexResetLedger {
        if (usage.fromCache || at <= (ledger.previous?.at ?: 0)) return ledger
        val old = ledger.previous
        fun timed(value: CodexQuotaWindow?): CodexQuotaWindow? = value?.let {
            if (it.resetAtEpochSeconds != null) it else it.copy(resetAtEpochSeconds = it.resetAfterSeconds?.let { seconds -> at + seconds })
        }
        val primary = timed(usage.primary)
        val weekly = timed(usage.secondary)
        return ledger.copy(
            startedAt = ledger.startedAt.takeIf { it > 0 } ?: at,
            previous = CodexResetObservation(at, primary ?: old?.primary, weekly ?: old?.weekly,
                usage.resetCreditsAvailable ?: old?.available, usage.resetCredits?.details ?: old?.credits.orEmpty()),
        )
    }
    fun register(ledger: CodexResetLedger, event: CodexResetEvent, now: Long): CodexResetLedger {
        require(event.source == CodexResetSource.Registered)
        require(event.id.isNotBlank() && event.id.length <= 128)
        require(event.kind in listOf(CodexResetKind.ManualUse, CodexResetKind.OfficialExtra))
        require(event.at in 1..(now + 120) && event.count in 1..999 && event.note.length <= 256)
        val events = (ledger.events.filterNot { it.id == event.id } + event).sortedByDescending { it.at }
        return ledger.copy(startedAt = ledger.startedAt.takeIf { it > 0 } ?: now,
            events = events.take(MAX_EVENTS), truncated = ledger.truncated || events.size > MAX_EVENTS)
    }
}
