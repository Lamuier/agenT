package com.lamuier.agenT.providers

import com.lamuier.agenT.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class ProviderHttpException(val code: Int) : IOException("HTTP $code")

/** Only fixed official endpoints are supplied by callers. Redirects never forward API keys. */
internal suspend fun providerGet(url: String, key: String? = null): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false; connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "agenTAndroid/${BuildConfig.VERSION_NAME}")
        if (key != null) connection.setRequestProperty("Authorization", "Bearer $key")
        val code = connection.responseCode
        if (code !in 200..299) throw ProviderHttpException(code)
        require(connection.contentLengthLong <= MAX_BYTES) { "Response too large" }
        val raw = connection.inputStream.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= MAX_BYTES) { "Response too large" }
                out.write(buffer, 0, count)
            }
            out.toString(Charsets.UTF_8.name())
        }
        JSONObject(raw)
    } finally { connection.disconnect() }
}
private const val MAX_BYTES = 1024 * 1024
