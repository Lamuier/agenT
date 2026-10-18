package com.lamuier.agenT.providers

import android.content.Context
import com.lamuier.agenT.R
import com.lamuier.agenT.network.StatusJsonParser
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class ProviderFailure(val message: Int, val retry: Boolean = false) {
    Permission(R.string.antigravity_permission_error), LimitsUnavailable(R.string.antigravity_limits_unavailable),
    Authentication(R.string.provider_auth_error), Credit(R.string.provider_credit_error),
    RateLimit(R.string.provider_rate_error, true), Offline(R.string.widget_cache_offline, true),
    Server(R.string.provider_usage_error, true), InvalidData(R.string.provider_usage_error),
    Storage(R.string.provider_storage_error);

    fun messageFor(provider: UsageProvider): Int =
        if (this == Authentication && provider == UsageProvider.Antigravity) R.string.connection_token_expired else message
}

internal data class ProviderUsageSnapshot(
    val credential: ProviderCredentialInfo? = null,
    val usage: ProviderUsage? = null,
    val failure: ProviderFailure? = null,
    val updated: Boolean = false,
) {
    val shouldRetry get() = failure?.retry == true
}

internal class ProviderRequestState {
    val changes = MutableStateFlow(0L)
    val flights = mutableMapOf<String, CompletableDeferred<ProviderUsageSnapshot>>()
}

/** One encrypted cache and one in-flight call per connection, shared by the app and widgets. */
internal class ProviderUsageRepository(
    context: Context,
    private val provider: UsageProvider,
    private val store: ProviderStore = ProviderStore(context.applicationContext, provider),
    private val request: suspend (String, String) -> JSONObject = { url, key -> providerGet(url, key) },
    private val shared: ProviderRequestState = states.getOrPut(provider) { ProviderRequestState() },
    private val antigravity: AntigravityDirectClient = AntigravityDirectClient(),
) {
    val changes = shared.changes.asStateFlow()

    fun cached(): ProviderUsageSnapshot = try {
        snapshotFrom(store.read())
    } catch (_: Exception) { ProviderUsageSnapshot(failure = ProviderFailure.Storage) }

    private fun snapshotFrom(connection: ProviderConnection?): ProviderUsageSnapshot {
        if (connection == null) return ProviderUsageSnapshot()
        val usage = connection.rawUsage?.let { raw -> connection.fetchedAt?.let { at ->
            runCatching { ProviderUsageParser.parse(provider, raw, at).copy(fromCache = true) }.getOrNull()
        } }
        return ProviderUsageSnapshot(connection.info, usage, connection.failure)
    }

    fun save(key: String, region: KimiRegion): ProviderCredentialInfo = store.save(
        if (provider == UsageProvider.Antigravity) AntigravityGrant.parse(key).encode() else key, region).info.also { changed() }
    fun clear() { store.clear(); changed() }
    fun reveal(): String = store.read()?.key.orEmpty()

    suspend fun refresh(force: Boolean = false, maxAgeSeconds: Long = 60): ProviderUsageSnapshot {
        val connection = try { store.read() } catch (_: Exception) {
            return ProviderUsageSnapshot(failure = ProviderFailure.Storage)
        } ?: return ProviderUsageSnapshot()
        val cached = snapshotFrom(connection)
        if (!force && cached.failure in listOf(ProviderFailure.Authentication, ProviderFailure.Permission)) return cached
        if (!force && cached.failure == null && cached.usage?.let { ageSeconds(it.fetchedAt) < maxAgeSeconds } == true) return cached
        val revision = connection.info.revision
        val (flight, start) = synchronized(shared) {
            shared.flights[revision]?.let { it to false } ?: CompletableDeferred<ProviderUsageSnapshot>().also {
                shared.flights[revision] = it
            }.let { it to true }
        }
        if (start) requestScope.launch {
            try {
                val raw = if (provider == UsageProvider.Antigravity) antigravity.fetch(connection.key) { secret ->
                    store.updateSecret(revision, secret)
                } else request(if (provider == UsageProvider.DeepSeek) "https://api.deepseek.com/user/balance"
                    else "${connection.info.region.baseUrl}/usages", connection.key)
                val at = StatusJsonParser.nowStamp()
                val usage = ProviderUsageParser.parse(provider, raw, at)
                val result = if (store.cache(revision, raw, at)) {
                    changed()
                    ProviderUsageSnapshot(connection.info, usage, updated = true)
                } else cached()
                flight.complete(result)
            } catch (error: CancellationException) {
                flight.cancel(error)
            } catch (error: Exception) {
                val failure = when {
                    error is AntigravityPermissionDenied -> ProviderFailure.Permission
                    error is AntigravityLimitsUnavailable -> ProviderFailure.LimitsUnavailable
                    error is ProviderHttpException && error.code == 403 && provider == UsageProvider.Antigravity -> ProviderFailure.Permission
                    else -> when ((error as? ProviderHttpException)?.code) {
                    401, 403 -> ProviderFailure.Authentication
                    402 -> ProviderFailure.Credit
                    429 -> ProviderFailure.RateLimit
                    in 500..599 -> ProviderFailure.Server
                    else -> if (error is IOException && error !is ProviderHttpException) ProviderFailure.Offline else ProviderFailure.InvalidData
                }
                }
                // Never attach a previous connection's error to a replacement connection.
                runCatching { if (store.recordFailure(revision, failure)) changed() }
                flight.complete(cached())
            } finally {
                synchronized(shared) { if (shared.flights[revision] === flight) shared.flights.remove(revision) }
            }
        }
        return flight.await()
    }

    private fun changed() { synchronized(shared) { shared.changes.value += 1 } }

    companion object {
        private val states = ConcurrentHashMap<UsageProvider, ProviderRequestState>()
        private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val STAMP_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fun ageSeconds(stamp: String): Long = runCatching {
            val instant = java.time.LocalDateTime.parse(stamp, STAMP_FORMATTER)
                .atZone(java.time.ZoneId.systemDefault()).toInstant()
            val diff = java.time.Duration.between(instant, java.time.Instant.now()).seconds
            if (diff < -5L) Long.MAX_VALUE else diff.coerceAtLeast(0L)
        }.getOrDefault(Long.MAX_VALUE)
    }
}
