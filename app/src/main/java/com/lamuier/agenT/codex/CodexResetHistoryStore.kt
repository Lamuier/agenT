package com.lamuier.agenT.codex

import android.content.Context
import com.lamuier.agenT.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

internal object CodexStorageLock { val monitor = Any() }

internal class CodexResetHistoryStore(
    context: Context,
    preferencesName: String = "agentt_codex_reset_history_v1",
    keyAlias: String = "agentt_codex_reset_history_key_v1",
) {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val crypto = CodexQuotaCrypto(keyAlias)
    fun read(owner: String): CodexResetLedger = synchronized(CodexStorageLock.monitor) {
        val raw = prefs.getString(owner, null) ?: return CodexResetLedger()
        CodexResetCodec.decode(crypto.decrypt(raw, aad(owner)))
    }
    fun update(owner: String, change: (CodexResetLedger) -> CodexResetLedger): CodexResetLedger =
        synchronized(CodexStorageLock.monitor) {
            val next = change(read(owner))
            val raw = crypto.encrypt(CodexResetCodec.encode(next), aad(owner))
            check(prefs.edit().putString(owner, raw).commit()) { "Reset history write failed" }
            next
        }
    fun clear() = synchronized(CodexStorageLock.monitor) { check(prefs.edit().clear().commit()) }
    private fun aad(owner: String) = "${BuildConfig.APPLICATION_ID}|codex-reset-history-v1|$owner"
}

internal object CodexResetCodec {
    fun encode(ledger: CodexResetLedger): String = JSONObject()
        .put("started", ledger.startedAt).put("anchor", ledger.subscriptionDay ?: JSONObject.NULL)
        .put("truncated", ledger.truncated)
        .put("previous", ledger.previous?.let { previous ->
            JSONObject().put("at", previous.at).put("available", previous.available ?: JSONObject.NULL)
                .put("primary", window(previous.primary)).put("weekly", window(previous.weekly))
                .put("credits", JSONArray().apply { previous.credits.forEach { put(credit(it)) } })
        } ?: JSONObject.NULL)
        .put("events", JSONArray().apply { ledger.events.forEach {
            put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("source", it.source.name)
                .put("at", it.at).put("count", it.count).put("note", it.note)
                .put("before", it.before ?: JSONObject.NULL).put("after", it.after ?: JSONObject.NULL))
        } }).toString()

    fun decode(raw: String): CodexResetLedger {
        val item = JSONObject(raw)
        val previous = item.optJSONObject("previous")?.let {
            CodexResetObservation(it.getLong("at"), readWindow(it.optJSONObject("primary")),
                readWindow(it.optJSONObject("weekly")), if (it.isNull("available")) null else it.getInt("available"),
                readCredits(it.optJSONArray("credits")))
        }
        val events = item.optJSONArray("events")
        return CodexResetLedger(item.optLong("started"), previous, buildList {
            if (events != null) for (index in 0 until events.length()) {
                val row = events.getJSONObject(index)
                add(CodexResetEvent(row.getString("id"), CodexResetKind.valueOf(row.getString("kind")),
                    CodexResetSource.valueOf(row.getString("source")), row.getLong("at"), row.optInt("count", 1),
                    row.optString("note"), number(row, "before"), number(row, "after")))
            }
        }.take(CodexResetTracker.MAX_EVENTS), if (item.isNull("anchor")) null else item.optInt("anchor").takeIf { it in 1..31 },
            item.optBoolean("truncated"))
    }
    fun credit(value: CodexResetCredit): JSONObject = JSONObject().put("id", value.id).put("status", value.status)
        .put("granted", value.grantedAt ?: JSONObject.NULL).put("expires", value.expiresAt ?: JSONObject.NULL)
        .put("title", value.title ?: JSONObject.NULL).put("redeemed", value.redeemedAt ?: JSONObject.NULL)
    fun readCredits(items: JSONArray?): List<CodexResetCredit> = buildList {
        if (items != null) for (index in 0 until items.length().coerceAtMost(500)) {
            val row = items.getJSONObject(index)
            add(CodexResetCredit(row.getString("id"), row.optString("status"),
                long(row, "granted"), long(row, "expires"), row.optString("title").takeIf { it.isNotBlank() && it != "null" },
                long(row, "redeemed")))
        }
    }
    private fun window(value: CodexQuotaWindow?): Any = value?.let {
        JSONObject().put("used", it.usedPercent).put("duration", it.limitWindowSeconds)
            .put("reset", it.resetAtEpochSeconds ?: JSONObject.NULL).put("after", it.resetAfterSeconds ?: JSONObject.NULL)
    } ?: JSONObject.NULL
    private fun readWindow(row: JSONObject?): CodexQuotaWindow? = row?.let {
        CodexQuotaWindow(it.getDouble("used"), it.getLong("duration"), long(it, "after"), long(it, "reset"))
    }
    private fun long(row: JSONObject, key: String): Long? = if (row.isNull(key)) null else row.optLong(key).takeIf { it > 0 }
    private fun number(row: JSONObject, key: String): Double? = if (row.isNull(key)) null else row.optDouble(key).takeIf { it.isFinite() }
}
