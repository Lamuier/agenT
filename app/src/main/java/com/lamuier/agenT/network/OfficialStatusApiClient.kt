package com.lamuier.agenT.network

import android.content.Context
import com.lamuier.agenT.BuildConfig
import com.lamuier.agenT.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class OfficialStatusProvider(val baseUrl: String, val cacheName: String) {
    Cursor("https://status.x.ai", "cursor_status_cache_v1"),
    Codex("https://status.openai.com", "codex_status_cache_v1"),
    DeepSeek("https://status.deepseek.com", "deepseek_status_cache_v1"),
    KimiCode("https://status.moonshot.cn", "kimi_code_status_cache_v1"),
}

enum class StatusResource(val filename: String) {
    Summary("summary.json"), Components("components.json"), Incidents("incidents.json"), Uptime("uptime.json"),
}

internal fun officialStatusRequestUrl(provider: OfficialStatusProvider, resource: StatusResource): String = when (provider) {
    OfficialStatusProvider.DeepSeek -> {
        val base = "${provider.baseUrl}/api/status-page/${ProviderStatusAdapters.DEEPSEEK_PAGE_ID}"
        when (resource) {
            StatusResource.Summary -> "$base/summary/active"
            StatusResource.Incidents -> {
                val end = System.currentTimeMillis() / 1000
                "$base/change/list?start_at_seconds=${end - 90L * 86400}&end_at_seconds=$end"
            }
            StatusResource.Components -> error("Components are included in the DeepSeek summary")
            StatusResource.Uptime -> error("DeepSeek status has no uptime feed")
        }
    }
    OfficialStatusProvider.Cursor -> when (resource) {
        StatusResource.Components -> error("Cursor components are included in the summary")
        else -> "https://data.x.ai/status/${resource.filename}"
    }
    else -> "${provider.baseUrl}/api/v2/${resource.filename}"
}

internal fun cursorLegacyStatusUrl(resource: StatusResource): String = when (resource) {
    StatusResource.Summary -> "https://status.cursor.com/api/v2/summary.json"
    StatusResource.Incidents -> "https://status.cursor.com/api/v2/incidents.json"
    else -> error("Legacy Cursor status has no ${resource.filename}")
}

/** Public official status requests never receive account credentials or cookies. */
class OfficialStatusApiClient(context: Context, private val provider: OfficialStatusProvider) {
    private val appContext = context.applicationContext

    suspend fun fetch(resource: StatusResource): JSONObject = get(officialStatusRequestUrl(provider, resource))

    /** Statuspage feed still published at status.cursor.com until Cursor components appear on status.x.ai. */
    suspend fun fetchLegacyCursor(resource: StatusResource): JSONObject {
        require(provider == OfficialStatusProvider.Cursor)
        return get(cursorLegacyStatusUrl(resource))
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "agenTAndroid/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(appContext.getString(R.string.status_error_http, code))
            if (connection.contentLengthLong > MAX_BYTES) {
                throw IOException(appContext.getString(R.string.status_error_response_too_large))
            }
            val raw = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) {
                        throw IOException(appContext.getString(R.string.status_error_response_too_large))
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_BYTES = 1024 * 1024 }
}
