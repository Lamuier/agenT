package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.network.OfficialStatusApiClient
import com.lamuier.agenT.network.OfficialStatusProvider
import com.lamuier.agenT.network.StatusJsonParser
import com.lamuier.agenT.network.StatusResource
import com.lamuier.agenT.network.ProviderStatusAdapters
import com.lamuier.agenT.network.CodexStatusScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class ServiceStatusRepository(
    context: Context,
    private val provider: OfficialStatusProvider = OfficialStatusProvider.Cursor,
) {
    private val api = OfficialStatusApiClient(context, provider)
    private val cacheStore = StatusCacheStore(context.applicationContext, provider.cacheName)
    private val shared = sharedStates.getOrPut(provider) { SharedState() }

    fun cached(): ServiceStatus? {
        memoryCached()?.let { return it }
        val disk = cacheStore.read() ?: return null
        return runCatching {
            scope(StatusJsonParser.parseCache(disk.rawJson, ageSeconds(disk.storedAtMillis)))
        }.getOrElse { cacheStore.clear(); null }
    }

    suspend fun fetch(forceRefresh: Boolean): ServiceStatus {
        if (!forceRefresh) memoryCached()?.let { return it }
        val (flight, startRequest) = synchronized(shared) {
            val existing = shared.inFlight
            if (existing != null) existing to false
            else CompletableDeferred<ServiceStatus>().also { shared.inFlight = it } to true
        }
        if (startRequest) {
            requestScope.launch {
                try {
                    val live = fetchLive()
                    synchronized(shared) { shared.cache = MemoryCacheEntry(System.currentTimeMillis(), live) }
                    flight.complete(live)
                } catch (error: Throwable) {
                    flight.completeExceptionally(error)
                } finally {
                    synchronized(shared) { if (shared.inFlight === flight) shared.inFlight = null }
                }
            }
        }
        return flight.await()
    }

    private fun memoryCached(): ServiceStatus? = synchronized(shared) {
        shared.cache?.takeIf { ageMillis(it.storedAtMillis) < MEMORY_TTL_MILLIS }
            ?.let { it.status.copy(fromCache = true, cacheAgeSeconds = ageSeconds(it.storedAtMillis)) }
    }

    private suspend fun optional(resource: StatusResource): JSONObject? = try {
        api.fetch(resource)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) { null }

    private suspend fun fetchLive(): ServiceStatus = coroutineScope {
        val loaded = if (provider == OfficialStatusProvider.Cursor) {
            val (summary, incidents) = cursorDocuments()
            LoadedStatus(summary, incidents, partialComponents = false)
        } else {
            val summaryRequest = async { api.fetch(StatusResource.Summary) }
            val incidentsRequest = async { optional(StatusResource.Incidents) }
            // OpenAI's summary omits some Codex components; fetch the complete list.
            val componentsRequest = if (provider in listOf(OfficialStatusProvider.Codex, OfficialStatusProvider.KimiCode)) {
                async { optional(StatusResource.Components) }
            } else null
            var summary = summaryRequest.await()
            val incidents = incidentsRequest.await()
            val components = componentsRequest?.await()?.optJSONArray("components")
            if (components != null) summary.put("components", components)
            val partialComponents = provider in listOf(OfficialStatusProvider.Codex, OfficialStatusProvider.KimiCode) && components == null
            val adapted = when (provider) {
                OfficialStatusProvider.DeepSeek -> ProviderStatusAdapters.deepSeek(summary, incidents)
                OfficialStatusProvider.KimiCode -> ProviderStatusAdapters.kimi(summary, incidents, partialComponents)
                else -> summary to incidents
            }
            LoadedStatus(adapted.first, adapted.second, partialComponents)
        }
        val fetchedAt = StatusJsonParser.nowStamp()
        val live = StatusJsonParser.parse(
            summary = loaded.summary, incidentsPayload = loaded.incidents, fetchedAt = fetchedAt,
            defaultStatusUrl = provider.baseUrl, partialComponents = loaded.partialComponents,
            maxRecentIncidents = if (provider == OfficialStatusProvider.Codex) Int.MAX_VALUE else 12,
        )
        runCatching {
            cacheStore.write(
                StatusJsonParser.toCacheJson(
                    loaded.summary, loaded.incidents, fetchedAt, provider.baseUrl, loaded.partialComponents,
                ),
                System.currentTimeMillis(),
            )
        }
        scope(live)
    }

    private suspend fun cursorDocuments(): Pair<JSONObject, JSONObject?> = coroutineScope {
        val summaryRequest = async {
            try {
                api.fetch(StatusResource.Summary)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        val incidentsRequest = async { optional(StatusResource.Incidents) }
        val uptimeRequest = async { optional(StatusResource.Uptime) }
        val summary = summaryRequest.await()
        val incidents = incidentsRequest.await()
        val uptime = uptimeRequest.await()
        if (summary != null) {
            val adapted = try {
                ProviderStatusAdapters.cursor(summary, incidents, uptime)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                null
            }
            if (adapted != null) return@coroutineScope adapted
        }
        val legacySummary = api.fetchLegacyCursor(StatusResource.Summary)
        val legacyIncidents = try {
            api.fetchLegacyCursor(StatusResource.Incidents)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        legacySummary to legacyIncidents
    }

    private data class LoadedStatus(
        val summary: JSONObject,
        val incidents: JSONObject?,
        val partialComponents: Boolean,
    )

    private fun scope(status: ServiceStatus): ServiceStatus =
        if (provider == OfficialStatusProvider.Codex) CodexStatusScope.apply(status) else status

    private fun ageMillis(timestamp: Long) = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    private fun ageSeconds(timestamp: Long) = (ageMillis(timestamp) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private data class MemoryCacheEntry(val storedAtMillis: Long, val status: ServiceStatus)
    private class SharedState {
        var cache: MemoryCacheEntry? = null
        var inFlight: CompletableDeferred<ServiceStatus>? = null
    }
    private companion object {
        const val MEMORY_TTL_MILLIS = 60_000L
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sharedStates = ConcurrentHashMap<OfficialStatusProvider, SharedState>()
    }
}
