package com.lamuier.agenT.codex

import java.net.URI
import java.time.Instant
import org.json.JSONObject
import org.json.JSONArray

/** Public announcements, never account redemption records or official quota balances. */
data class CodexPublicReset(val id: String, val type: String, val at: Long, val text: String,
    val sourceType: String, val author: String?, val url: String?)
data class CodexPublicResetSnapshot(val events: List<CodexPublicReset>, val fetchedAt: Long,
    val truncated: Boolean = false, val fromCache: Boolean = false)
internal data class CodexPublicResetPage(val events: List<CodexPublicReset>, val hasMore: Boolean, val cursor: String?)

internal object CodexPublicResetJson {
    const val MAX_EVENTS = 500
    private const val CACHE_SCHEMA = 2
    fun supported(events: List<CodexPublicReset>): List<CodexPublicReset> =
        events.filter { it.type in listOf("regular", "banked") }.distinctBy { it.id }
    fun isCurrentCache(raw: String?): Boolean = runCatching {
        raw != null && JSONObject(raw).getInt("schema") == CACHE_SCHEMA
    }.getOrDefault(false)
    private fun string(root: JSONObject, name: String, max: Int): String = (root.opt(name) as? String)
        ?.takeIf { it.isNotBlank() && it.length <= max } ?: error("Invalid $name")
    private fun event(row: JSONObject): CodexPublicReset {
        val source = row.getJSONObject("source")
        val type = string(row, "reset_type", 16).also { require(it in listOf("regular", "banked")) }
        val sourceType = string(source, "type", 16).also { require(it in listOf("x_post", "observed")) }
        val text = (row.opt("text") as? String) ?: error("Invalid text")
        require(text.length <= 8000)
        return CodexPublicReset(string(row, "id", 64), type,
            Instant.parse(string(row, "announced_at", 64)).epochSecond,
            text.filter { !it.isISOControl() || it == '\n' || it == '\t' }, sourceType,
            (source.opt("author") as? String)?.take(64),
            (source.opt("url") as? String)?.takeIf(CodexPublicResetLinks::isAllowed))
    }
    fun page(raw: String): CodexPublicResetPage {
        val root = JSONObject(raw)
        val meta = root.getJSONObject("meta")
        require(meta.getString("api_version") == "v1")
        Instant.parse(meta.getString("generated_at"))
        val rows = root.getJSONArray("data")
        require(rows.length() <= 100)
        val events = supported((0 until rows.length()).map { event(rows.getJSONObject(it)) })
        val pagination = root.getJSONObject("pagination")
        val more = pagination.get("has_more") as? Boolean ?: error("Invalid pagination")
        val cursor = (pagination.opt("next_cursor") as? String)?.also { require(validCursor(it)) }
        require(!more || cursor != null)
        return CodexPublicResetPage(events, more, cursor)
    }
    fun validCursor(value: String) = value.length in 1..1024 && value.matches(Regex("[A-Za-z0-9_-]+"))
    fun encode(snapshot: CodexPublicResetSnapshot): String = JSONObject().put("schema", CACHE_SCHEMA)
        .put("fetched_at", snapshot.fetchedAt).put("truncated", snapshot.truncated)
        .put("events", JSONArray().apply { supported(snapshot.events).take(MAX_EVENTS).forEach { row ->
            put(JSONObject().put("id", row.id).put("reset_type", row.type)
                .put("announced_at", Instant.ofEpochSecond(row.at).toString()).put("text", row.text)
                .put("source", JSONObject().put("type", row.sourceType).put("author", row.author).put("url", row.url)))
        } }).toString()
    fun decode(raw: String): CodexPublicResetSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("schema") in listOf(1, CACHE_SCHEMA))
        val rows = root.getJSONArray("events")
        require(rows.length() <= MAX_EVENTS)
        val fetched = root.getLong("fetched_at").also { require(it > 0) }
        return CodexPublicResetSnapshot(supported((0 until rows.length()).map { event(rows.getJSONObject(it)) }),
            fetched, root.optBoolean("truncated"), fromCache = true)
    }
}

/** Only fixed provider/announcement links, with no credentials or app tokens attached. */
internal object CodexPublicResetLinks {
    const val CREDIT_URL = "https://codex-resets.com"
    fun isAllowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return@runCatching false
        when (uri.host?.lowercase()) {
            "codex-resets.com" -> true
            "x.com", "twitter.com" -> uri.path.matches(Regex("/thsottiaux/status/[0-9]+/?"))
            else -> false
        }
    }.getOrDefault(false)
}

internal data class CodexResetTimelineEntry(val at: Long, val public: CodexPublicReset)
internal object CodexResetTimeline {
    fun entries(public: List<CodexPublicReset>, range: CodexResetRange?,
        allPublicHistory: Boolean = false): List<CodexResetTimelineEntry> {
        if (range == null && !allPublicHistory) return emptyList()
        fun inRange(at: Long) = range != null && at >= range.start && at < range.end
        return CodexPublicResetJson.supported(public).filter { allPublicHistory || inRange(it.at) }
            .map { CodexResetTimelineEntry(it.at, it) }.sortedByDescending { it.at }
    }
}
