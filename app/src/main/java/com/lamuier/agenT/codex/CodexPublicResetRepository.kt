package com.lamuier.agenT.codex

import android.content.Context
import com.lamuier.agenT.BuildConfig
import com.lamuier.agenT.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Isolated public GET client: no access to account/token stores, no cookies, no redirects. */
internal class CodexPublicResetRepository(context: Context) {
    private val context = context.applicationContext
    private val cache = this.context.getSharedPreferences("codex_public_resets_v1", Context.MODE_PRIVATE)
    fun cached(): CodexPublicResetSnapshot? = runCatching {
        cache.getString("snapshot", null)?.let(CodexPublicResetJson::decode)
    }.getOrNull()
    fun fetch(force: Boolean): CodexPublicResetSnapshot {
        if (!force) {
            val previous = cached()
            if (previous != null && CodexPublicResetJson.isCurrentCache(cache.getString("snapshot", null)) &&
                System.currentTimeMillis() - previous.fetchedAt in 0 until 15 * 60_000L) return previous
        }
        return requests.run(context.noBackupFilesDir.absolutePath) { fetchOnce(true) }
    }
    private fun fetchOnce(force: Boolean): CodexPublicResetSnapshot {
        val now = System.currentTimeMillis()
        val previous = cached()
        // Older caches may have discarded banked grants; fetch the full response before reusing their ETag.
        val currentCache = CodexPublicResetJson.isCurrentCache(cache.getString("snapshot", null))
        if (!force && previous != null && currentCache && now - previous.fetchedAt in 0 until 15 * 60_000L) return previous
        if (now < cache.getLong("retry_at", 0)) throw IOException(context.getString(R.string.reset_public_retry_later))
        val rows = mutableListOf<CodexPublicReset>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var etag: String? = null
        var more = false
        for (pageIndex in 0 until 5) {
            val suffix = cursor?.let { "&cursor=$it" }.orEmpty()
            val connection = URL("https://codex-resets.com/api/v1/resets?limit=100&order=desc$suffix").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "agenTAndroid/${BuildConfig.VERSION_NAME}")
                if (pageIndex == 0 && previous != null && currentCache) {
                    val storedEtag = cache.getString("etag", null)
                    val etagHeader = acceptableEtag(storedEtag)
                    if (etagHeader != null) {
                        connection.setRequestProperty("If-None-Match", etagHeader)
                    } else if (storedEtag != null) {
                        cache.edit().remove("etag").apply()
                    }
                }
                val code = connection.responseCode
                if (code == 304 && pageIndex == 0 && previous != null && currentCache) {
                    return save(previous.copy(fetchedAt = now, fromCache = false), cache.getString("etag", null))
                }
                if (code == 429) {
                    val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60
                    cache.edit().putLong("retry_at", now + seconds * 1000).apply()
                    throw IOException(context.getString(R.string.reset_public_retry_later))
                }
                if (code != 200) throw IOException(context.getString(R.string.reset_public_http_error, code))
                val maxBytes = 1024 * 1024
                if (connection.contentLengthLong > maxBytes) throw IOException(context.getString(R.string.reset_public_format_error))
                val raw = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        if (output.size() + size > maxBytes) throw IOException(context.getString(R.string.reset_public_format_error))
                        output.write(buffer, 0, size)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val page = try { CodexPublicResetJson.page(raw) } catch (_: Exception) {
                    throw IOException(context.getString(R.string.reset_public_format_error))
                }
                if (pageIndex == 0) etag = acceptableEtag(connection.getHeaderField("ETag"))
                rows += page.events
                more = page.hasMore
                if (!more) break
                cursor = page.cursor
                if (!seenCursors.add(cursor!!)) throw IOException(context.getString(R.string.reset_public_format_error))
            } finally { connection.disconnect() }
        }
        return save(CodexPublicResetSnapshot(rows.distinctBy { it.id }.sortedByDescending { it.at }
            .take(CodexPublicResetJson.MAX_EVENTS), now, truncated = more), etag)
    }
    private fun save(snapshot: CodexPublicResetSnapshot, etag: String?): CodexPublicResetSnapshot {
        // Do not overwrite a valid snapshot until the complete bounded traversal has succeeded.
        val editor = cache.edit()
            .putString("snapshot", CodexPublicResetJson.encode(snapshot))
            .remove("retry_at")
        val safeEtag = acceptableEtag(etag)
        if (safeEtag == null) editor.remove("etag") else editor.putString("etag", safeEtag)
        check(editor.commit()) { context.getString(R.string.reset_public_cache_error) }
        return snapshot
    }

    private companion object {
        val requests = com.lamuier.agenT.data.InFlightRequests<String, CodexPublicResetSnapshot>()
    }

    /** Header values are echoed back. Reject controls so a bad ETag cannot stick and fail every later request. */
    private fun acceptableEtag(value: String?): String? {
        val etag = value?.trim()?.takeIf { it.length in 1..512 } ?: return null
        if (etag.any { it.isISOControl() || it == '\u007F' }) return null
        return etag
    }
}
