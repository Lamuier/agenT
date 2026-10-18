package com.lamuier.agenT.providers

import com.lamuier.agenT.BuildConfig
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** An imported grant, never a Google password. No client credentials are bundled with the app. */
internal class AntigravityGrant(private val json: JSONObject) {
    val access: String get() = json.getString("access_token")
    val refresh: String? get() = json.text("refresh_token")
    val client: String? get() = json.text("client_id")
    val clientSecret: String? get() = json.text("client_secret")
    val subject: String? get() = json.text("subject")
    val email: String? get() = json.text("email")
    val project: String? get() = json.text("project_id")
    val expiry: Long? get() = if (json.has("expiry_date")) json.getLong("expiry_date") else null
    val canRefresh get() = refresh != null && client != null
    fun encode(): String = json.toString()
    fun updated(block: JSONObject.() -> Unit): AntigravityGrant = AntigravityGrant(JSONObject(encode()).apply(block))
    override fun toString() = "AntigravityGrant([redacted])"

    companion object {
        fun parse(value: String): AntigravityGrant {
            require(value.length <= 65536)
            val raw = value.trim()
            val input = if (raw.startsWith("{")) JSONObject(raw) else JSONObject().put("access_token", raw)
            val out = JSONObject()
            listOf("access_token" to "accessToken", "refresh_token" to "refreshToken", "client_id" to "clientId",
                "client_secret" to "clientSecret", "project_id" to "projectId", "email" to "email", "subject" to "subject").forEach { (snake, camel) ->
                val key = if (input.has(snake)) snake else camel
                if (input.has(key) && !input.isNull(key)) {
                    val text = input.get(key) as? String ?: throw IllegalArgumentException("Invalid grant")
                    val clean = text.trim()
                    require(clean.isNotEmpty() && clean.length <= 16384 && clean.none { it.isWhitespace() || it.isISOControl() })
                    out.put(snake, clean)
                }
            }
            require(out.text("access_token")?.length?.let { it >= 16 } == true)
            out.text("client_id")?.let { require(it.endsWith(".apps.googleusercontent.com")) }
            val expiryKey = listOf("expiry_date", "expiresAt", "expires_at", "expiry").firstOrNull { input.has(it) && !input.isNull(it) }
            expiryKey?.let { key ->
                val valueAt = input.get(key).toString()
                val epoch = valueAt.toBigDecimalOrNull()?.let {
                    val multiplier = if (key == "expires_at" && it < BigDecimal("100000000000")) BigDecimal(1000) else BigDecimal.ONE
                    it.multiply(multiplier).setScale(0, RoundingMode.FLOOR).longValueExact()
                } ?: Instant.parse(valueAt).toEpochMilli()
                require(epoch >= 0)
                out.put("expiry_date", epoch)
            }
            return AntigravityGrant(out)
        }
    }
}

internal enum class AntigravityRoute(val url: String) {
    Token("https://oauth2.googleapis.com/token"),
    Identity("https://www.googleapis.com/oauth2/v3/userinfo"),
    Account("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"),
    Summary("https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary"),
    Buckets("https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"),
}
internal class AntigravityRequest(val route: AntigravityRoute, val bearer: String? = null,
    val json: JSONObject? = null, val form: Map<String, String>? = null) {
    override fun toString() = "AntigravityRequest(route=$route, credentials=[redacted])"
}
internal class AntigravityLimitsUnavailable : Exception()
internal class AntigravityPermissionDenied : Exception()

/** Query-only Cloud Code methods. No onboarding, prompts, account mutation or availability-only fallback. */
internal class AntigravityDirectClient(
    private val request: suspend (AntigravityRequest) -> JSONObject = { antigravityRequest(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetch(secret: String, persist: (String) -> Boolean): JSONObject {
        var grant = AntigravityGrant.parse(secret)
        suspend fun renew() {
            if (!grant.canRefresh) throw ProviderHttpException(401)
            val form = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to requireNotNull(grant.refresh),
                "client_id" to requireNotNull(grant.client))
            grant.clientSecret?.let { form["client_secret"] = it }
            val response = try { request(AntigravityRequest(AntigravityRoute.Token, form = form)) }
                catch (e: ProviderHttpException) { if (e.code == 400) throw ProviderHttpException(401) else throw e }
            val access = response.text("access_token") ?: throw ProviderHttpException(401)
            require(access.length >= 16 && access.none { it.isWhitespace() || it.isISOControl() })
            val seconds = BigDecimal(response.get("expires_in").toString()).setScale(0, RoundingMode.FLOOR).longValueExact()
            require(seconds in 1..604800)
            grant = AntigravityGrant.parse(grant.updated {
                put("access_token", access); put("expiry_date", now() + seconds * 1000)
                response.text("refresh_token")?.let { put("refresh_token", it) }
            }.encode())
            check(persist(grant.encode())) { "Connection replaced" }
        }
        var renewed = false
        if (grant.expiry?.let { it <= now() + 60_000 } == true) { renew(); renewed = true }
        while (true) {
            try {
                val identity = request(AntigravityRequest(AntigravityRoute.Identity, grant.access))
                val subject = identity.text("sub") ?: throw ProviderHttpException(401)
                require(subject.length <= 255 && subject.none { it.isWhitespace() || it.isISOControl() })
                if (grant.subject != null && subject != grant.subject ||
                    grant.email != null && !grant.email.equals(identity.text("email"), ignoreCase = true)) throw ProviderHttpException(401)
                grant = grant.updated { put("subject", subject) }
                check(persist(grant.encode())) { "Connection replaced" }
                val metadata = JSONObject().put("ideType", "ANTIGRAVITY").put("platform", "PLATFORM_UNSPECIFIED").put("pluginType", "GEMINI")
                val account = request(AntigravityRequest(AntigravityRoute.Account, grant.access, JSONObject().put("metadata", metadata)))
                val ineligible = account.optJSONArray("ineligibleTiers")
                if (ineligible != null && (0 until ineligible.length()).any {
                        ineligible.optJSONObject(it)?.optString("reasonCode") == "GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT" }) throw AntigravityPermissionDenied()
                val discovered = when (val p = account.opt("cloudaicompanionProject")) {
                    is String -> p.takeIf { it.isNotBlank() }
                    is JSONObject -> p.text("id") ?: p.text("projectId")
                    else -> null
                }
                // The server's account project is authoritative over imported cached metadata.
                val project = discovered ?: grant.project
                if (project != null) {
                    require(project.length <= 240 && project.none { it.isWhitespace() || it.isISOControl() })
                    grant = grant.updated { put("project_id", project) }
                }
                check(persist(grant.encode())) { "Connection replaced" }
                val body = JSONObject().apply { project?.let { put("project", it) } }
                val summary = try {
                    val raw = request(AntigravityRequest(AntigravityRoute.Summary, grant.access, body))
                    AntigravityQuotaParser.normalize(raw, summary = true)
                } catch (_: AntigravityLimitsUnavailable) { null }
                catch (e: ProviderHttpException) { if (e.code in listOf(400, 404, 501)) null else throw e }
                return summary ?: AntigravityQuotaParser.normalize(
                    request(AntigravityRequest(AntigravityRoute.Buckets, grant.access, body)), summary = false)
            } catch (e: ProviderHttpException) {
                if (e.code != 401 || renewed || !grant.canRefresh) throw e
                renew(); renewed = true
            }
        }
    }
}

/** Normalize only measured quota fields; never cache tokens, identity responses or arbitrary server bodies. */
internal object AntigravityQuotaParser {
    fun normalize(raw: JSONObject, summary: Boolean): JSONObject {
        val status = raw.optJSONObject("error")?.optInt("code", 0)
        if (raw.has("error") && !raw.isNull("error")) {
            if (status == 401) throw ProviderHttpException(401)
            if (status in listOf(403, 7)) throw AntigravityPermissionDenied()
            throw AntigravityLimitsUnavailable()
        }
        if (raw.has("code") && raw.opt("code").toString() !in listOf("0", "200", "OK", "SUCCESS")) throw AntigravityLimitsUnavailable()
        val windows = JSONArray()
        if (summary) {
            val payload = raw.optJSONObject("response") ?: raw.optJSONObject("summary") ?: raw
            val groups = payload.optJSONArray("groups") ?: throw AntigravityLimitsUnavailable()
            require(groups.length() <= 64)
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val name = label(group.text("displayName") ?: group.text("name") ?: "Quota")
                val buckets = group.optJSONArray("buckets") ?: continue
                val ids = mutableSetOf<String>()
                require(buckets.length() <= 64)
                for (j in 0 until buckets.length()) {
                    val bucket = buckets.getJSONObject(j)
                    val id = bucket.text("bucketId") ?: bucket.text("id") ?: continue
                    require(ids.add(id))
                    val title = label(bucket.text("displayName") ?: bucket.text("name") ?: id)
                    val fraction = bucket.opt("remainingFraction").takeUnless { it == null || it == JSONObject.NULL }
                        ?: bucket.optJSONObject("remaining")?.opt("remainingFraction")
                    require(!bucket.has("disabled") || bucket.opt("disabled") is Boolean)
                    windows.put(window("$i:$id", "$name · $title", fraction, bucket.text("resetTime"), bucket.optBoolean("disabled", false)))
                }
            }
        } else {
            val buckets = raw.optJSONArray("buckets") ?: throw AntigravityLimitsUnavailable()
            require(buckets.length() <= 256)
            val ids = mutableMapOf<String, JSONObject>()
            for (i in 0 until buckets.length()) {
                val bucket = buckets.getJSONObject(i)
                val id = bucket.text("modelId") ?: continue
                val next = window(id, label(id), bucket.opt("remainingFraction"), bucket.text("resetTime"), false)
                // Keep separate reset periods rather than silently dropping one window for the same model.
                val key = "$id:${next.text("resetAt").orEmpty()}"
                val old = ids[key]
                if (old == null || old.isNull("remaining") || !next.isNull("remaining") &&
                    BigDecimal(next.getString("remaining")) < BigDecimal(old.getString("remaining"))) ids[key] = next
            }
            ids.toSortedMap().values.forEachIndexed { i, item -> windows.put(item.put("id", "$i:${item.getString("id")}")) }
        }
        val normalized = JSONObject().put("windows", windows)
        if (parse(normalized, "").windows.none { it.usedRatio != null }) throw AntigravityLimitsUnavailable()
        return normalized
    }
    fun parse(raw: JSONObject, at: String): ProviderUsage {
        val items = raw.getJSONArray("windows")
        require(items.length() in 1..256)
        val windows = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val remaining = item.text("remaining")?.let { BigDecimal(it) }
            val invalid = remaining != null && (remaining < BigDecimal.ZERO || remaining > BigDecimal.ONE)
            val disabled = item.getBoolean("disabled")
            QuotaWindow(item.getString("id"), remaining?.takeUnless { invalid || disabled }?.let { BigDecimal.ONE.subtract(it) },
                item.text("resetAt"), inconsistent = invalid, label = label(item.getString("label")), disabled = disabled)
        }
        require(windows.map { it.id }.distinct().size == windows.size)
        return ProviderUsage(UsageProvider.Antigravity, at, windows = windows)
    }
    private fun window(id: String, name: String, fraction: Any?, reset: String?, disabled: Boolean): JSONObject {
        val number = fraction?.takeUnless { it == JSONObject.NULL }?.toString()?.let {
            require(it.length <= 256)
            BigDecimal(it).also { value -> require(value.precision() <= 64 && value.scale() in -64..64) }
        }
        val instant = reset?.let { runCatching { Instant.parse(it).toString() }.getOrNull() }
        return JSONObject().put("id", label(id)).put("label", name).put("remaining", number?.toPlainString() ?: JSONObject.NULL)
            .put("resetAt", instant ?: JSONObject.NULL).put("disabled", disabled)
    }
    private fun label(value: String): String { require(value.isNotBlank() && value.length <= 240 && value.none { it.isISOControl() }); return value }
}

private fun JSONObject.text(key: String): String? = opt(key)?.takeUnless { it == JSONObject.NULL }?.let { it as? String }?.takeIf { it.isNotBlank() }

internal suspend fun antigravityRequest(request: AntigravityRequest,
    open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): JSONObject = withContext(Dispatchers.IO) {
    require((request.route == AntigravityRoute.Token) == (request.form != null))
    require((request.route == AntigravityRoute.Token) == (request.bearer == null))
    val connection = open(URL(request.route.url))
    try {
        connection.requestMethod = if (request.route == AntigravityRoute.Identity) "GET" else "POST"
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false; connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        val agent = if (request.route in listOf(AntigravityRoute.Account, AntigravityRoute.Summary, AntigravityRoute.Buckets))
            "antigravity/hub/2.9.1 android/arm64 agenTAndroid/${BuildConfig.VERSION_NAME}" else "agenTAndroid/${BuildConfig.VERSION_NAME}"
        connection.setRequestProperty("User-Agent", agent)
        request.bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (connection.requestMethod == "POST") {
            val form = request.form
            val body = if (form != null) form.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" } else requireNotNull(request.json).toString()
            connection.setRequestProperty("Content-Type", if (form != null) "application/x-www-form-urlencoded" else "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        if (status !in 200..299) throw ProviderHttpException(status)
        require(connection.contentLengthLong <= 1024 * 1024)
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 1024 * 1024); output.write(buffer, 0, count)
            }
        }
        JSONObject(output.toString("UTF-8"))
    } finally { connection.disconnect() }
}
