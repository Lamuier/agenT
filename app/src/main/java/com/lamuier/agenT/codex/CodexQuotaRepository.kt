package com.lamuier.agenT.codex

import android.content.Context
import android.content.res.Resources
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lamuier.agenT.BuildConfig
import com.lamuier.agenT.R
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.TokenUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class CodexQuotaApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message)

/**
 * Codex 配额的数据边界。凭据与解析后的缓存都使用 Android Keystore 加密，
 * 网络只访问 ChatGPT 官方用量接口。
 */
class CodexQuotaRepository internal constructor(
    context: Context,
    private val isolated: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val tokenStore by lazy { CodexTokenStore(appContext, isolated) }
    private val cacheStore by lazy { CodexQuotaCacheStore(appContext, isolated) }
    private val api = CodexQuotaApiClient(appContext)
    private val usageHistoryStore by lazy { com.lamuier.agenT.usage.UsageHistoryStore(appContext, isolated) }

    fun usageHistory(days: Int = 90): List<com.lamuier.agenT.usage.UsageObservation> = synchronized(CodexStorageLock.monitor) {
        val snapshot = tokenStore.snapshot() ?: return@synchronized emptyList()
        usageHistoryStore.read("codex", CodexResetIdentity.key(snapshot.accessToken, snapshot.accountId), days = days)
    }
    private val resetStore by lazy { if (isolated) {
        CodexResetHistoryStore(
            appContext,
            preferencesName = QA_RESET_PREFERENCES,
            keyAlias = QA_RESET_KEY_ALIAS,
        )
    } else {
        CodexResetHistoryStore(appContext)
    } }
    @Volatile var resetTrackingError: String? = null
        private set

    fun credentialInfo(): CodexCredentialInfo? {
        val snapshot = tokenStore.snapshot() ?: return null
        val expired = CodexTokenValidator.isExpired(snapshot.accessToken)
        if (expired && !snapshot.invalid) tokenStore.markInvalid(snapshot.revision, true, snapshot.accessToken)
        return CodexCredentialInfo(
            expired = expired,
            invalid = snapshot.invalid || expired,
            updatedAt = Instant.ofEpochMilli(snapshot.updatedAtMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER),
            accountId = snapshot.accountId.orEmpty(),
        )
    }

    fun tokenRevision(): Long = tokenStore.revision()

    fun cachedUsage(revision: Long): CodexQuotaOverview? {
        val snapshot = tokenStore.snapshot() ?: return null
        if (snapshot.revision != revision || snapshot.invalid) return null
        val cached = cacheStore.read(revision) ?: return null
        return cached.overview.copy(
            fromCache = true,
            cacheAgeSeconds = ((System.currentTimeMillis() - cached.storedAtMillis)
                .coerceAtLeast(0L) / 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
        )
    }

    fun saveCredential(accessToken: String, accountId: String): CodexCredentialInfo = synchronized(CodexStorageLock.monitor) {
        val previous = tokenStore.snapshot()
        val nextOwner = CodexResetIdentity.key(accessToken, accountId)
        tokenStore.save(accessToken, accountId)
        if (previous != null && CodexResetIdentity.key(previous.accessToken, previous.accountId) != nextOwner) usageHistoryStore.clear("codex")
        cacheStore.clear()
        resetTrackingError = null
        checkNotNull(credentialInfo())
    }

    fun clearCredential() = synchronized(CodexStorageLock.monitor) {
        usageHistoryStore.clear("codex")
        tokenStore.clear()
        cacheStore.clear()
        resetStore.clear()
        resetTrackingError = null
        if (!isolated) {
            runCatching {
                com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(appContext).clearCodexResetAlerts()
            }
        }
    }

    fun resetHistory(): CodexResetLedger? = synchronized(CodexStorageLock.monitor) {
        val snapshot = tokenStore.snapshot() ?: return null
        resetStore.read(CodexResetIdentity.key(snapshot.accessToken, snapshot.accountId))
    }

    fun registerReset(revision: Long, event: CodexResetEvent): CodexResetLedger = editResets(revision) {
        CodexResetTracker.register(it, event, System.currentTimeMillis() / 1000)
    }
    fun deleteReset(revision: Long, eventId: String): CodexResetLedger = editResets(revision) {
        it.copy(events = it.events.filterNot { event -> event.id == eventId && event.source != CodexResetSource.Official })
    }
    fun setSubscriptionDay(revision: Long, day: Int): CodexResetLedger {
        require(day in 1..31)
        return editResets(revision) { it.copy(subscriptionDay = day) }
    }
    private fun editResets(revision: Long, edit: (CodexResetLedger) -> CodexResetLedger): CodexResetLedger =
        synchronized(CodexStorageLock.monitor) {
            val snapshot = tokenStore.snapshot() ?: error(appContext.getString(R.string.codex_error_token_missing))
            check(snapshot.revision == revision) { appContext.getString(R.string.codex_error_credential_changed) }
            resetStore.update(CodexResetIdentity.key(snapshot.accessToken, snapshot.accountId), edit)
        }

    fun revealToken(): String = tokenStore.snapshot()?.accessToken
        ?: throw IllegalStateException(appContext.getString(R.string.codex_error_token_missing))

    fun fetchUsage(revision: Long, scheduleResetRefresh: Boolean = true): CodexQuotaOverview {
        val usage = usageRequests.run(Triple(appContext.noBackupFilesDir.absolutePath, isolated, revision)) {
            fetchUsageOnce(revision, false)
        }
        synchronized(CodexStorageLock.monitor) {
            val current = tokenStore.snapshot()
            check(current != null && current.revision == revision) { appContext.getString(R.string.codex_error_credential_changed) }
            if (scheduleResetRefresh && !isolated) runCatching {
                com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(appContext).onCodexQuotaObserved(
                    usage, emptyList(), CodexResetIdentity.key(current.accessToken, current.accountId), revision, true)
            }
        }
        return usage
    }

    private fun fetchUsageOnce(revision: Long, scheduleResetRefresh: Boolean): CodexQuotaOverview {
        val snapshot = tokenStore.snapshot()
            ?: throw CodexQuotaApiException(401, appContext.getString(R.string.codex_error_token_missing))
        if (snapshot.revision != revision) {
            throw IOException(appContext.getString(R.string.codex_error_credential_changed))
        }
        if (CodexTokenValidator.isExpired(snapshot.accessToken)) {
            tokenStore.markInvalid(revision, true, snapshot.accessToken)
            throw CodexQuotaApiException(401, appContext.getString(R.string.codex_error_token_invalid))
        }

        val historyTicket = usageHistoryStore.generation()
        return try {
            val payload = api.usage(snapshot.accessToken, snapshot.accountId.takeIf(String::isNotBlank))
            val overview = CodexQuotaParser.parse(payload)
            synchronized(CodexStorageLock.monitor) {
                val current = tokenStore.snapshot()
                if (current?.revision != revision || current.accessToken != snapshot.accessToken || current.accountId != snapshot.accountId) {
                    throw IOException(appContext.getString(R.string.codex_error_credential_changed))
                }
                tokenStore.markInvalid(revision, false, snapshot.accessToken)
                cacheStore.write(revision, CodexQuotaParser.toCache(overview), System.currentTimeMillis())
                val owner = CodexResetIdentity.key(snapshot.accessToken, snapshot.accountId)
                runCatching { usageHistoryStore.record("codex", owner, com.lamuier.agenT.usage.UsageAnalysis.codex(overview, System.currentTimeMillis()), historyTicket) }.onFailure { usageHistoryStore.reportFailure("codex", owner) }
                var newlyConfirmed = emptyList<CodexResetEvent>()
                runCatching {
                    resetStore.update(owner) {
                        val now = System.currentTimeMillis() / 1000
                        val next = CodexResetTracker.observe(it, overview, now)
                        newlyConfirmed = com.lamuier.agenT.notification.CodexResetNoticeModel.newlyConfirmed(it, next, now)
                        next
                    }
                }.onSuccess { resetTrackingError = null }
                    .onFailure { resetTrackingError = appContext.getString(R.string.reset_error_tracking) }
                if (!isolated) runCatching {
                    com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(appContext)
                        .onCodexQuotaObserved(overview, newlyConfirmed, owner, revision, scheduleResetRefresh)
                }
            }
            overview
        } catch (error: CodexQuotaApiException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                tokenStore.markInvalid(revision, true, snapshot.accessToken)
            }
            throw error
        }
    }

    private companion object {
        val usageRequests = com.lamuier.agenT.data.InFlightRequests<Triple<String, Boolean, Long>, CodexQuotaOverview>()
        const val QA_RESET_PREFERENCES = "agentt_qa_codex_reset_history_v1"
        const val QA_RESET_KEY_ALIAS = "agentt_qa_codex_reset_history_key_v1"
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

private class CodexQuotaApiClient(private val context: Context) {
    fun usage(accessToken: String, accountId: String?): JSONObject {
        val connection = URL(USAGE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("User-Agent", "AgenTAndroid/${BuildConfig.VERSION_NAME}")
            accountId?.let { connection.setRequestProperty("ChatGPT-Account-ID", it) }

            val status = connection.responseCode
            if (status in 300..399) {
                throw CodexQuotaApiException(status, context.getString(R.string.codex_error_api_redirect))
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream == null) {
                ""
            } else {
                stream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) {
                            throw CodexQuotaApiException(
                                status,
                                context.getString(R.string.codex_error_response_too_large),
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            }
            if (status !in 200..299) throw CodexQuotaApiException(status, errorMessage(status))
            return JSONObject(body.trim().ifEmpty { "{}" })
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(status: Int): String = when (status) {
        401, 403 -> context.getString(R.string.codex_error_token_invalid)
        404 -> context.getString(R.string.codex_error_endpoint_changed)
        429 -> context.getString(R.string.codex_error_rate_limited)
        in 500..599 -> context.getString(R.string.codex_error_service_unavailable, status)
        else -> context.getString(R.string.codex_error_request_failed, status)
    }

    private companion object {
        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

internal object CodexQuotaParser {
    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun parse(payload: JSONObject): CodexQuotaOverview {
        val rateLimit = payload.optJSONObject("rate_limit") ?: findRateLimit(payload)
        val resetCredits = parseResetCredits(payload.optJSONObject("rate_limit_reset_credits")
            ?: payload.optJSONObject("rateLimitResetCredits"))
        val observedAt = System.currentTimeMillis() / 1000
        fun timed(window: CodexQuotaWindow?) = window?.let {
            if (it.resetAtEpochSeconds != null) it else it.copy(resetAtEpochSeconds = it.resetAfterSeconds?.let { seconds -> observedAt + seconds })
        }
        return CodexQuotaOverview(
            planType = payload.optString("plan_type").takeIf(String::isNotBlank),
            primary = timed(parseWindow(rateLimit.optJSONObject("primary_window"))),
            secondary = timed(parseWindow(rateLimit.optJSONObject("secondary_window"))),
            additional = parseAdditional(rateLimit.optJSONArray("additional_rate_limits")).mapNotNull(::timed),
            resetCreditsAvailable = resetCredits?.availableCount,
            fetchedAt = Instant.now().atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER),
            resetCredits = resetCredits,
        )
    }

    fun toCache(overview: CodexQuotaOverview): String = JSONObject()
        .put("plan_type", overview.planType ?: JSONObject.NULL)
        .put("fetched_at", overview.fetchedAt)
        .put("primary", overview.primary?.let(::windowJson) ?: JSONObject.NULL)
        .put("secondary", overview.secondary?.let(::windowJson) ?: JSONObject.NULL)
        .put("additional", JSONArray().apply { overview.additional.forEach { put(windowJson(it)) } })
        .put("reset_credits", overview.resetCreditsAvailable ?: JSONObject.NULL)
        .put("reset_details", overview.resetCredits?.let {
            JSONObject().put("available_count", it.availableCount ?: JSONObject.NULL)
                .put("credits", it.details?.let { rows -> JSONArray().apply { rows.forEach { row ->
                    put(JSONObject().put("id", row.id).put("status", row.status)
                        .put("granted_at", row.grantedAt ?: JSONObject.NULL).put("expires_at", row.expiresAt ?: JSONObject.NULL)
                        .put("title", row.title ?: JSONObject.NULL).put("redeemed_at", row.redeemedAt ?: JSONObject.NULL))
                } } } ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .toString()

    fun fromCache(raw: String): CodexQuotaOverview {
        val item = JSONObject(raw)
        return CodexQuotaOverview(
            planType = item.optString("plan_type").takeIf(String::isNotBlank),
            primary = parseWindow(item.optJSONObject("primary")),
            secondary = parseWindow(item.optJSONObject("secondary")),
            additional = parseAdditional(item.optJSONArray("additional")),
            resetCreditsAvailable = nonnegativeInt(item.opt("reset_credits")),
            fetchedAt = item.optString("fetched_at"),
            fromCache = true,
            resetCredits = parseResetCredits(item.optJSONObject("reset_details")),
        )
    }

    private fun parseResetCredits(item: JSONObject?): CodexResetCredits? {
        item ?: return null
        fun count(key: String): Int? = nonnegativeInt(item.opt(key))
        val available = count("available_count") ?: count("availableCount")
        val array = item.optJSONArray("credits")
        val rows = array?.let { buildList {
            val seen = mutableSetOf<String>()
            for (index in 0 until it.length().coerceAtMost(500)) {
                val row = it.optJSONObject(index) ?: continue
                val id = row.optString("id").takeIf { value -> value.isNotBlank() && value.length <= 128 &&
                    value.none(Char::isISOControl) } ?: continue
                if (!seen.add(id)) continue
                fun time(snake: String, camel: String): Long? =
                    validEpoch(row.opt(snake)) ?: validEpoch(row.opt(camel))
                add(CodexResetCredit(id, row.optString("status").take(40), time("granted_at", "grantedAt"),
                    time("expires_at", "expiresAt"), row.optString("title").takeIf { value -> value.isNotBlank() && value != "null" }?.take(160),
                    time("redeemed_at", "redeemedAt")))
            }
        } }
        return CodexResetCredits(available, rows)
    }

    private fun nonnegativeInt(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
    }
    // Dates outside the Android date formatter's supported operational range are not trustworthy metadata.
    private fun validEpoch(value: Any?): Long? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it in 1.0..253402300799.0 && it % 1.0 == 0.0 }?.toLong()
    }

    private fun findRateLimit(payload: JSONObject): JSONObject {
        val limits = payload.optJSONArray("rate_limits") ?: return JSONObject()
        var first: JSONObject? = null
        for (index in 0 until limits.length()) {
            val item = limits.optJSONObject(index) ?: continue
            if (first == null) first = item
            if (item.optString("limit_id").equals("codex", ignoreCase = true)) return item
        }
        return first ?: JSONObject()
    }

    private fun parseWindow(item: JSONObject?): CodexQuotaWindow? {
        item ?: return null
        val used = item.optDouble("used_percent", Double.NaN)
        val duration = item.optLong("limit_window_seconds")
        if (!used.isFinite() || used < 0 || duration <= 0L) return null
        return CodexQuotaWindow(
            usedPercent = used,
            limitWindowSeconds = duration,
            resetAfterSeconds = item.optLong("reset_after_seconds").takeIf { it > 0L },
            resetAtEpochSeconds = item.optLong("reset_at").takeIf { it > 0L },
        )
    }

    private fun parseAdditional(items: JSONArray?): List<CodexQuotaWindow> = buildList {
        if (items == null) return@buildList
        for (index in 0 until items.length()) parseWindow(items.optJSONObject(index))?.let(::add)
    }

    private fun windowJson(window: CodexQuotaWindow) = JSONObject()
        .put("used_percent", window.usedPercent)
        .put("limit_window_seconds", window.limitWindowSeconds)
        .put("reset_after_seconds", window.resetAfterSeconds ?: JSONObject.NULL)
        .put("reset_at", window.resetAtEpochSeconds ?: JSONObject.NULL)
}

private class CodexTokenStore(context: Context, isolated: Boolean = false) {
    private val resources = AppLocale.wrap(context).resources
    private val preferencesName = if (isolated) QA_PREFERENCES else PREFERENCES
    private val preferences = context.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val crypto = CodexQuotaCrypto(if (isolated) QA_TOKEN_KEY_ALIAS else TOKEN_KEY_ALIAS)
    private val lock = CodexStorageLock.monitor

    fun snapshot(): Snapshot? = synchronized(lock) {
        val raw = preferences.getString(KEY_CREDENTIALS, null) ?: run {
            cachedSnapshots.remove(preferencesName)
            return null
        }
        cachedSnapshots[preferencesName]?.takeIf { it.first == raw }?.let { return it.second }
        val item = JSONObject(raw)
        Snapshot(
            accessToken = crypto.decrypt(item.getString("token"), aad("token")),
            accountId = item.optString("account")
                .takeIf(String::isNotBlank)
                ?.let { crypto.decrypt(it, aad("account")) }
                .orEmpty(),
            revision = item.optLong("revision", 1L),
            invalid = item.optBoolean("invalid"),
            updatedAtMillis = item.optLong("updated_at"),
        ).also {
            cachedSnapshots[preferencesName] = raw to it
        }
    }

    fun save(accessToken: String, accountId: String): Snapshot = synchronized(lock) {
        val token = CodexTokenValidator.requireValid(accessToken, resources)
        val account = accountId.trim()
        val currentRevision = runCatching {
            JSONObject(preferences.getString(KEY_CREDENTIALS, "{}")!!).optLong("revision")
        }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val item = JSONObject()
            .put("token", crypto.encrypt(token, aad("token")))
            .put("account", account.takeIf(String::isNotBlank)?.let { crypto.encrypt(it, aad("account")) } ?: "")
            .put("revision", currentRevision + 1L)
            .put("invalid", false)
            .put("updated_at", now)
        val encoded = item.toString()
        check(preferences.edit().putString(KEY_CREDENTIALS, encoded).commit())
        Snapshot(token, account, currentRevision + 1L, false, now).also {
            cachedSnapshots[preferencesName] = encoded to it
        }
    }

    fun revision(): Long = synchronized(lock) {
        val raw = preferences.getString(KEY_CREDENTIALS, null) ?: run {
            cachedSnapshots.remove(preferencesName)
            return 0L
        }
        cachedSnapshots[preferencesName]?.takeIf { it.first == raw }?.let { return it.second.revision }
        runCatching { JSONObject(raw).optLong("revision", 1L) }.getOrDefault(0L)
    }

    fun markInvalid(revision: Long, invalid: Boolean, expectedToken: String): Unit = synchronized(lock) {
        val raw = preferences.getString(KEY_CREDENTIALS, null) ?: return
        val cached = cachedSnapshots[preferencesName]?.takeIf { it.first == raw }?.second
        val item = JSONObject(raw)
        if (item.optLong("revision") != revision || item.optBoolean("invalid") == invalid) return
        val currentToken = cached?.accessToken ?: crypto.decrypt(item.getString("token"), aad("token"))
        if (currentToken != expectedToken) return
        item.put("invalid", invalid)
        val encoded = item.toString()
        check(preferences.edit().putString(KEY_CREDENTIALS, encoded).commit())
        if (cached != null) {
            cachedSnapshots[preferencesName] = encoded to cached.copy(invalid = invalid)
        } else {
            cachedSnapshots.remove(preferencesName)
        }
    }

    fun clear() = synchronized(lock) {
        cachedSnapshots.remove(preferencesName)
        check(preferences.edit().remove(KEY_CREDENTIALS).commit())
    }

    private fun aad(field: String) = "${BuildConfig.APPLICATION_ID}|agentt-codex-token-v1|$field"

    data class Snapshot(
        val accessToken: String,
        val accountId: String,
        val revision: Long,
        val invalid: Boolean,
        val updatedAtMillis: Long,
    )

    private companion object {
        const val PREFERENCES = "agentt_codex_token_v1"
        const val QA_PREFERENCES = "agentt_qa_codex_token_v1"
        const val KEY_CREDENTIALS = "credentials"
        const val TOKEN_KEY_ALIAS = "agentt_codex_token_key_v1"
        const val QA_TOKEN_KEY_ALIAS = "agentt_qa_codex_token_key_v1"
        val cachedSnapshots = ConcurrentHashMap<String, Pair<String, Snapshot>>()
    }
}

private class CodexQuotaCacheStore(context: Context, isolated: Boolean = false) {
    private val preferencesName = if (isolated) QA_PREFERENCES else PREFERENCES
    private val preferences = context.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val crypto = CodexQuotaCrypto(if (isolated) QA_CACHE_KEY_ALIAS else CACHE_KEY_ALIAS)

    fun read(revision: Long): CacheEntry? {
        val storageKey = key(revision)
        val encrypted = preferences.getString(storageKey, null) ?: return null
        val cacheKey = "$preferencesName|$storageKey"
        cachedEntries[cacheKey]?.takeIf { it.first == encrypted }?.let { return it.second }
        return runCatching {
            val wrapper = JSONObject(crypto.decrypt(encrypted, aad(revision)))
            val payload = wrapper.getString("payload")
            val storedAt = wrapper.getLong("stored_at")
            CacheEntry(payload, storedAt, CodexQuotaParser.fromCache(payload)).also {
                cachedEntries[cacheKey] = encrypted to it
            }
        }.getOrElse {
            cachedEntries.remove(cacheKey)
            preferences.edit().remove(storageKey).apply()
            null
        }
    }

    fun write(revision: Long, payload: String, storedAtMillis: Long) {
        val wrapper = JSONObject().put("payload", payload).put("stored_at", storedAtMillis)
        val encrypted = crypto.encrypt(wrapper.toString(), aad(revision))
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { oldKey ->
            cachedEntries.remove("$preferencesName|$oldKey")
            editor.remove(oldKey)
        }
        val targetKey = key(revision)
        check(editor.putString(targetKey, encrypted).commit())
        runCatching {
            cachedEntries["$preferencesName|$targetKey"] =
                encrypted to CacheEntry(payload, storedAtMillis, CodexQuotaParser.fromCache(payload))
        }
    }

    fun clear() {
        cachedEntries.keys.removeIf { it.startsWith("$preferencesName|") }
        check(preferences.edit().clear().commit())
    }

    private fun key(revision: Long) = "$KEY_PREFIX$revision"
    private fun aad(revision: Long) = "${BuildConfig.APPLICATION_ID}|agentt-codex-cache-v1|$revision"

    data class CacheEntry(
        val payload: String,
        val storedAtMillis: Long,
        val overview: CodexQuotaOverview,
    )

    private companion object {
        const val PREFERENCES = "agentt_codex_cache_v1"
        const val QA_PREFERENCES = "agentt_qa_codex_cache_v1"
        const val KEY_PREFIX = "usage_r"
        const val CACHE_KEY_ALIAS = "agentt_codex_cache_key_v1"
        const val QA_CACHE_KEY_ALIAS = "agentt_qa_codex_cache_key_v1"
        val cachedEntries = ConcurrentHashMap<String, Pair<String, CacheEntry>>()
    }
}

internal class CodexQuotaCrypto(private val keyAlias: String) {
    fun encrypt(value: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad.toByteArray())
        return listOf(cipher.iv, cipher.doFinal(value.toByteArray())).joinToString(":") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    fun decrypt(value: String, aad: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val key = getExistingKey()
            ?: throw IllegalStateException("Android Keystore key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getExistingKey(): SecretKey? {
        cachedKeys[keyAlias]?.let { return it }
        return synchronized(keyLock) {
            cachedKeys[keyAlias]?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(keyAlias, null) as? SecretKey)?.also { cachedKeys[keyAlias] = it }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKeys[keyAlias]?.let { return it }
        return synchronized(keyLock) {
            cachedKeys[keyAlias]?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            val resolved = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
            cachedKeys[keyAlias] = resolved
            resolved
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val keyLock = Any()
        val cachedKeys = ConcurrentHashMap<String, SecretKey>()
    }
}

internal object CodexTokenValidator {
    private val safePattern = Regex("[A-Za-z0-9._~=-]+")
    private val expiryPattern = Regex("\"exp\"\\s*:\\s*(\\d+)")

    fun requireValid(raw: String, resources: Resources? = null): String {
        val token = raw.trim()
        require(token.length in 32..8192) {
            resources?.getString(R.string.error_token_length) ?: "Access Token 长度不正确"
        }
        require(safePattern.matches(token)) {
            resources?.getString(R.string.error_token_unsafe) ?: "Access Token 包含不安全字符"
        }
        return token
    }

    fun isExpired(token: String): Boolean {
        val expiry = expiryPattern.find(TokenUtils.jwtPayload(token))?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return false
        return System.currentTimeMillis() / 1_000L >= expiry - 60L
    }
}
