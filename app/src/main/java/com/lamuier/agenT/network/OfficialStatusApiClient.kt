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
    Cursor("https://status.cursor.com", "cursor_status_cache_v1"),
    Codex("https://status.openai.com", "codex_status_cache_v1"),
}

enum class StatusResource(val filename: String) {
    Summary("summary.json"), Components("components.json"), Incidents("incidents.json"),
}

/** Public official status requests never receive account credentials or cookies. */
class OfficialStatusApiClient(context: Context, private val provider: OfficialStatusProvider) {
    private val appContext = context.applicationContext

    suspend fun fetch(resource: StatusResource): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("${provider.baseUrl}/api/v2/${resource.filename}")
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
