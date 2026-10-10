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
        val events = (0 until rows.length()).map { event(rows.getJSONObject(it)) }.distinctBy { it.id }
        val pagination = root.getJSONObject("pagination")
        val more = pagination.get("has_more") as? Boolean ?: error("Invalid pagination")
        val cursor = (pagination.opt("next_cursor") as? String)?.also { require(validCursor(it)) }
        require(!more || cursor != null)
        return CodexPublicResetPage(events, more, cursor)
    }
    fun validCursor(value: String) = value.length in 1..1024 && value.matches(Regex("[A-Za-z0-9_-]+"))
    fun encode(snapshot: CodexPublicResetSnapshot): String = JSONObject().put("schema", 1)
        .put("fetched_at", snapshot.fetchedAt).put("truncated", snapshot.truncated)
        .put("events", JSONArray().apply { snapshot.events.take(MAX_EVENTS).forEach { row ->
            put(JSONObject().put("id", row.id).put("reset_type", row.type)
                .put("announced_at", Instant.ofEpochSecond(row.at).toString()).put("text", row.text)
                .put("source", JSONObject().put("type", row.sourceType).put("author", row.author).put("url", row.url)))
        } }).toString()
    fun decode(raw: String): CodexPublicResetSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("events")
        require(rows.length() <= MAX_EVENTS)
        val fetched = root.getLong("fetched_at").also { require(it > 0) }
        return CodexPublicResetSnapshot((0 until rows.length()).map { event(rows.getJSONObject(it)) }.distinctBy { it.id },
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

internal data class CodexResetTimelineEntry(val at: Long, val local: CodexResetEvent? = null, val public: CodexPublicReset? = null)
internal enum class CodexResetTimelineSource { All, Local, Public }
internal object CodexResetTimeline {
    fun entries(local: List<CodexResetEvent>, public: List<CodexPublicReset>, range: CodexResetRange?,
        source: CodexResetTimelineSource, kind: CodexResetKind?, allPublicHistory: Boolean = false): List<CodexResetTimelineEntry> {
        if (range == null && !allPublicHistory) return emptyList()
        fun inRange(at: Long) = range != null && at >= range.start && at < range.end
        return buildList {
            if (source != CodexResetTimelineSource.Public) local.filter { inRange(it.at) && (kind == null || it.kind == kind) }
                .forEach { add(CodexResetTimelineEntry(it.at, local = it)) }
            if (source != CodexResetTimelineSource.Local && kind == null) public.distinctBy { it.id }.filter { allPublicHistory || inRange(it.at) }
                .forEach { add(CodexResetTimelineEntry(it.at, public = it)) }
        }.sortedByDescending { it.at }
    }
}
