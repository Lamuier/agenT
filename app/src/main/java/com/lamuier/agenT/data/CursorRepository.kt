package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.R
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.network.ApiException
import com.lamuier.agenT.network.CursorApiClient
import com.lamuier.agenT.network.AgenTAssembler
import com.lamuier.agenT.network.TasksJsonParser
import com.lamuier.agenT.model.UsageWindow
import com.lamuier.agenT.notification.TaskActivityModel
import com.lamuier.agenT.network.UsageJsonParser
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.TokenUtils
import com.lamuier.agenT.util.UsageHistoryWindows
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
import java.io.IOException

class AccountRevisionChangedException : IOException("Cursor 账号已发生变化，已忽略旧请求结果")

class CursorRepository(context: Context) {
    private val appContext = context.applicationContext
    private val api = CursorApiClient(appContext)
    private val accountStore by lazy { EncryptedAccountStore(appContext) }
    private val usageCache by lazy { UsageCacheStore(appContext) }
    private val tasksCache by lazy { TasksCacheStore(appContext) }
    private val historyStore by lazy { com.lamuier.agenT.usage.UsageHistoryStore(appContext) }
    private val migrationCompleted by lazy { clearMigratedAccountUsageCaches(); true }

    private fun historyOwner(snapshot: EncryptedAccountStore.AccountSnapshot): String =
        com.lamuier.agenT.usage.UsageHistoryStore.identity("${snapshot.id}:${TokenUtils.userId(snapshot.accessToken).ifBlank { snapshot.accessToken }}")

    fun usageHistory(accountId: Int, days: Int = 90): List<com.lamuier.agenT.usage.UsageObservation> {
        val snapshot = accountStore.snapshot(accountId)
        val rows = historyStore.read("cursor", historyOwner(snapshot), days = days)
        ensureCurrent(snapshot)
        return rows
    }

    fun listAccounts(): List<CursorAccount> {
        check(migrationCompleted)
        return accountStore.listAccounts()
    }

    fun addAccount(alias: String, accessToken: String): CursorAccount =
        accountStore.create(alias, accessToken)

    fun updateAccount(accountId: Int, alias: String?, accessToken: String?): CursorAccount {
        val previous = accountStore.snapshot(accountId)
        val updated = accountStore.withCurrentRevision(accountId, previous.revision) {
            val next = accountStore.update(accountId, alias, accessToken)
            if (historyOwner(previous) != historyOwner(accountStore.snapshot(accountId))) historyStore.clear("cursor")
            next
        } ?: throw AccountRevisionChangedException()
        invalidateUsage(accountId)
        invalidateTasks(accountId)
        return updated
    }

    fun deleteAccount(accountId: Int) {
        val revision = accountStore.revision(accountId)
        accountStore.withCurrentRevision(accountId, revision) {
            require(accountStore.delete(accountId)) { AppLocale.string(appContext, R.string.error_account_not_found) }
            historyStore.clear("cursor")
        } ?: throw AccountRevisionChangedException()
        invalidateUsage(accountId)
        invalidateTasks(accountId)
    }

    fun selectedAccountId(): Int? = accountStore.selectedAccountId()

    fun revealAccessToken(accountId: Int): String = accountStore.revealAccessToken(accountId)

    fun saveSelectedAccountId(accountId: Int?) {
        accountStore.saveSelectedAccountId(accountId)
    }

    fun accountRevision(accountId: Int): Long = accountStore.revision(accountId)

    fun cachedUsage(
        accountId: Int,
        allowInvalidCredential: Boolean = false,
    ): AgenTOverview? {
        val snapshot = runCatching { accountStore.snapshot(accountId) }.getOrNull() ?: return null
        if (snapshot.tokenInvalid || TokenUtils.isExpired(snapshot.accessToken)) {
            markCredentialInvalid(snapshot)
            if (!allowInvalidCredential) return null
        }
        val cached = usageCache.read(accountId, snapshot.revision) ?: return null
        val overview = synchronized(memoryLock) {
            parsedUsageCache[accountId]?.takeIf {
                it.revision == snapshot.revision && it.rawJson == cached.rawJson
            }?.overview
        } ?: runCatching { UsageJsonParser.parseUsage(cached.rawJson, isLocalCache = true) }
            .getOrElse {
                usageCache.remove(accountId)
                return null
            }.also { parsed ->
                synchronized(memoryLock) {
                    parsedUsageCache[accountId] = ParsedUsageCacheEntry(snapshot.revision, cached.rawJson, parsed)
                }
            }
        if (overview.accountId != accountId || !accountStore.isCurrent(accountId, snapshot.revision)) {
            usageCache.remove(accountId)
            return null
        }
        return overview.asCached(ageSeconds(cached.storedAtMillis), isLocal = true)
    }

    suspend fun fetchUsage(accountId: Int, forceRefresh: Boolean): AgenTOverview {
        val snapshot = accountStore.snapshot(accountId)
        if (TokenUtils.isExpired(snapshot.accessToken) || (snapshot.tokenInvalid && !forceRefresh)) {
            markCredentialInvalid(snapshot)
            throw ApiException(401, AppLocale.string(appContext, R.string.error_api_token_invalid))
        }

        if (!forceRefresh) {
            freshMemoryEntry(snapshot)?.let { entry ->
                return entry.overview.asCached(ageSeconds(entry.storedAtMillis), isLocal = false)
            }
        }

        val requestKey = NetworkRequestKey(accountId, snapshot.revision)
        val live = singleFlight(requestKey) {
            val historyTicket = historyStore.generation()
            try {
                val fetched = fetchLive(snapshot)
                ensureCurrent(snapshot)
                if (!accountStore.markTokenInvalid(accountId, snapshot.revision, invalid = false)) {
                    throw AccountRevisionChangedException()
                }
                val storedAtMillis = System.currentTimeMillis()
                val normalizedJson = UsageJsonParser.toJson(fetched)
                accountStore.withCurrentRevision(accountId, snapshot.revision) {
                    runCatching {
                        usageCache.write(accountId, snapshot.revision, normalizedJson, storedAtMillis)
                    }
                } ?: throw AccountRevisionChangedException()
                if (!accountStore.isCurrent(accountId, snapshot.revision)) {
                    usageCache.remove(accountId, snapshot.revision)
                    throw AccountRevisionChangedException()
                }
                accountStore.withCurrentRevision(accountId, snapshot.revision) {
                    runCatching {
                        historyStore.record("cursor", historyOwner(snapshot), com.lamuier.agenT.usage.UsageAnalysis.cursor(fetched, storedAtMillis), historyTicket)
                    }.onFailure { historyStore.reportFailure("cursor", historyOwner(snapshot)) }
                }
                fetched
            } catch (error: ApiException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    if (accountStore.isCurrent(accountId, snapshot.revision)) {
                        if (!accountStore.markTokenInvalid(accountId, snapshot.revision, invalid = true)) {
                            throw AccountRevisionChangedException()
                        }
                    } else {
                        throw AccountRevisionChangedException()
                    }
                }
                throw error
            }
        }
        ensureCurrent(snapshot)
        val storedAtMillis = System.currentTimeMillis()
        synchronized(memoryLock) {
            memoryCache[accountId] = MemoryCacheEntry(snapshot.revision, storedAtMillis, live)
        }
        if (!accountStore.isCurrent(accountId, snapshot.revision)) {
            invalidateUsage(accountId, snapshot.revision)
            throw AccountRevisionChangedException()
        }
        return live
    }

    fun cachedTasks(
        accountId: Int,
        allowInvalidCredential: Boolean = false,
    ): AgenTasks? {
        val snapshot = runCatching { accountStore.snapshot(accountId) }.getOrNull() ?: return null
        if (snapshot.tokenInvalid || TokenUtils.isExpired(snapshot.accessToken)) {
            markCredentialInvalid(snapshot)
            if (!allowInvalidCredential) return null
        }
        val cached = tasksCache.read(accountId, snapshot.revision) ?: return null
        val baseTasks = synchronized(tasksMemoryLock) {
            parsedTasksCache[accountId]?.takeIf {
                it.revision == snapshot.revision && it.rawJson == cached.rawJson
            }?.tasks
        } ?: runCatching {
            TasksJsonParser.parseStored(
                cached.rawJson,
                accountId = accountId,
                fetchedAt = "",
                cacheAgeSeconds = 0,
            )
        }.getOrElse {
            tasksCache.remove(accountId)
            return null
        }.also { parsed ->
            synchronized(tasksMemoryLock) {
                parsedTasksCache[accountId] = ParsedTasksCacheEntry(snapshot.revision, cached.rawJson, parsed)
            }
        }
        val stored = baseTasks.copy(
            fetchedAt = TasksJsonParser.nowStamp(),
            cacheAgeSeconds = ageSeconds(cached.storedAtMillis),
        )
        if (stored.accountId != accountId || !accountStore.isCurrent(accountId, snapshot.revision)) {
            tasksCache.remove(accountId)
            return null
        }
        return stored
    }

    /** Persist acknowledgement without refreshing cache age or removing task history. */
    internal fun acknowledgeTaskCompletions(accountId: Int, revision: Long, completions: Map<String, Long>): Boolean =
        synchronized(tasksMemoryLock) {
            if (completions.isEmpty() || !accountStore.isCurrent(accountId, revision)) return@synchronized false
            val cached = tasksCache.read(accountId, revision) ?: return@synchronized false
            val tasks = TasksJsonParser.parseStored(cached.rawJson, accountId, "", ageSeconds(cached.storedAtMillis))
            val acknowledged = TaskActivityModel.acknowledge(tasks, completions)
            if (tasks.completedAtMillis == acknowledged.completedAtMillis) return@synchronized false
            val written = accountStore.withCurrentRevision(accountId, revision) {
                tasksCache.write(accountId, revision, TasksJsonParser.toJson(acknowledged), cached.storedAtMillis)
            } ?: return@synchronized false
            if (!written) return@synchronized false
            if (!accountStore.isCurrent(accountId, revision)) {
                tasksCache.remove(accountId, revision)
                return@synchronized false
            }
            tasksMemoryCache[accountId]?.takeIf { it.revision == revision }?.let { entry ->
                tasksMemoryCache[accountId] = entry.copy(tasks = TaskActivityModel.acknowledge(entry.tasks, completions))
            }
            true
        }

    suspend fun fetchTasks(accountId: Int, forceRefresh: Boolean): AgenTasks {
        val snapshot = accountStore.snapshot(accountId)
        if (TokenUtils.isExpired(snapshot.accessToken) || (snapshot.tokenInvalid && !forceRefresh)) {
            markCredentialInvalid(snapshot)
            throw ApiException(401, AppLocale.string(appContext, R.string.error_api_token_invalid))
        }

        if (!forceRefresh) {
            freshTasksMemoryEntry(snapshot)?.let { entry ->
                return entry.tasks.copy(
                    fromCache = true,
                    cacheAgeSeconds = ageSeconds(entry.storedAtMillis),
                )
            }
        }

        val requestKey = NetworkRequestKey(accountId, snapshot.revision)
        val live = singleFlightTasks(requestKey) {
            try {
                val payload = api.agentTasks(snapshot.accessToken)
                ensureCurrent(snapshot)
                if (!accountStore.markTokenInvalid(accountId, snapshot.revision, invalid = false)) {
                    throw AccountRevisionChangedException()
                }
                val parsed = TasksJsonParser.parse(payload, accountId = snapshot.id)
                synchronized(tasksMemoryLock) {
                    ensureCurrent(snapshot)
                    // Read after the network request: a confirmation during the request must stay cleared.
                    val previous = cachedTasks(accountId)
                    val storedAtMillis = System.currentTimeMillis()
                    val fetched = TaskActivityModel.observe(previous, parsed, storedAtMillis)
                    accountStore.withCurrentRevision(accountId, snapshot.revision) {
                        runCatching {
                            tasksCache.write(accountId, snapshot.revision, TasksJsonParser.toJson(fetched), storedAtMillis)
                        }
                    } ?: throw AccountRevisionChangedException()
                    if (!accountStore.isCurrent(accountId, snapshot.revision)) {
                        tasksCache.remove(accountId, snapshot.revision)
                        throw AccountRevisionChangedException()
                    }
                    tasksMemoryCache[accountId] = TasksMemoryEntry(snapshot.revision, storedAtMillis, fetched)
                    fetched
                }
            } catch (error: ApiException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    if (accountStore.isCurrent(accountId, snapshot.revision)) {
                        if (!accountStore.markTokenInvalid(accountId, snapshot.revision, invalid = true)) {
                            throw AccountRevisionChangedException()
                        }
                    } else {
                        throw AccountRevisionChangedException()
                    }
                }
                throw error
            }
        }
        ensureCurrent(snapshot)
        if (!accountStore.isCurrent(accountId, snapshot.revision)) {
            invalidateTasks(accountId, snapshot.revision)
            throw AccountRevisionChangedException()
        }
        runCatching { com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(appContext).refreshCopyFromCache() }
        return live
    }

    private suspend fun singleFlight(
        key: NetworkRequestKey,
        block: suspend () -> AgenTOverview,
    ): AgenTOverview {
        val candidate = CompletableDeferred<AgenTOverview>()
        val active = activeUsageRequests.putIfAbsent(key, candidate)
        if (active != null) return active.await()
        requestScope.launch {
            try {
                candidate.complete(block())
            } catch (error: Throwable) {
                candidate.completeExceptionally(error)
            } finally {
                activeUsageRequests.remove(key, candidate)
            }
        }
        return candidate.await()
    }

    private suspend fun singleFlightTasks(
        key: NetworkRequestKey,
        block: suspend () -> AgenTasks,
    ): AgenTasks {
        val candidate = CompletableDeferred<AgenTasks>()
        val active = activeTasksRequests.putIfAbsent(key, candidate)
        if (active != null) return active.await()
        requestScope.launch {
            try {
                candidate.complete(block())
            } catch (error: Throwable) {
                candidate.completeExceptionally(error)
            } finally {
                activeTasksRequests.remove(key, candidate)
            }
        }
        return candidate.await()
    }

    private suspend fun fetchLive(
        snapshot: EncryptedAccountStore.AccountSnapshot,
    ): AgenTOverview = coroutineScope {
        val period = async {
            api.connectRpc(snapshot.accessToken, "GetCurrentPeriodUsage")
        }
        val plan = async {
            api.connectRpc(snapshot.accessToken, "GetPlanInfo")
        }
        val grants = async {
            optionalPayload(authenticationFailureIsFatal = false) {
                api.connectRpc(snapshot.accessToken, "GetUsageLimitStatusAndActiveGrants")
            }
        }
        val aggregations = async {
            // Token 明细为增强信息：失败时仍展示花费 / 百分比用量。
            optionalPayload(authenticationFailureIsFatal = false) {
                api.connectRpc(snapshot.accessToken, "GetAggregatedUsageEvents")
            }
        }
        val grokBot = async {
            // Grok Bot 周额度独立于月度用量池：401/403 不得拖垮整次刷新。
            optionalPayload(authenticationFailureIsFatal = false) {
                api.connectRpc(snapshot.accessToken, "GetSandUsageStatus")
            }
        }
        val stripe = async {
            // Stripe is supplementary. Its cookie contract may fail independently
            // even while the primary Bearer endpoints still accept the token.
            optionalPayload(authenticationFailureIsFatal = false) { api.stripe(snapshot.accessToken) }
        }

        val periodUsage = period.await()
        val grantsResult = grants.await()
        val aggregationsResult = aggregations.await()
        val grokBotResult = grokBot.await()
        val stripeResult = stripe.await()
        AgenTAssembler.assemble(
            accountId = snapshot.id,
            alias = snapshot.alias,
            periodUsage = periodUsage,
            planPayload = plan.await(),
            grantsPayload = grantsResult.payload,
            stripePayload = stripeResult.payload,
            aggregationsPayload = aggregationsResult.payload,
            grokBotPayload = grokBotResult.payload,
            // Additional periods are fetched by the visible usage page with its actual range.
            previousCyclePayload = null,
            previousCycleStartMs = null,
            previousCycleEndMs = null,
            calendarMonthPayload = null,
            calendarMonthStartMs = null,
            calendarMonthEndMs = null,
            calendarMonthKey = null,
            partialData = grantsResult.partial || stripeResult.partial ||
                aggregationsResult.partial || grokBotResult.partial,
        )
    }

    suspend fun fetchHistoryWindow(
        accountId: Int,
        startMs: Long,
        endMs: Long,
        yearMonth: String? = null,
    ): UsageWindow {
        val snapshot = accountStore.snapshot(accountId)
        val payload = optionalPayload(authenticationFailureIsFatal = false) {
            api.connectRpc(
                snapshot.accessToken,
                "GetAggregatedUsageEvents",
                aggregationsRangeBody(startMs, endMs),
            )
        }
        ensureCurrent(snapshot)
        if (payload.payload == null) throw IOException(AppLocale.string(appContext, R.string.error_load_history))
        return AgenTAssembler.usageWindow(
            startMs = startMs,
            endMs = endMs,
            payload = payload.payload,
            yearMonth = yearMonth,
        ) ?: UsageWindow(
            start = null,
            end = null,
            yearMonth = yearMonth,
            tokenUsage = null,
        )
    }

    private fun aggregationsRangeBody(startMs: Long, endMs: Long): JSONObject =
        JSONObject()
            .put("startDate", startMs.toString())
            .put("endDate", endMs.toString())

    private suspend fun optionalPayload(
        authenticationFailureIsFatal: Boolean,
        block: suspend () -> JSONObject,
    ): OptionalPayload = try {
        OptionalPayload(block(), partial = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ApiException) {
        if (authenticationFailureIsFatal && (error.statusCode == 401 || error.statusCode == 403)) throw error
        OptionalPayload(payload = null, partial = true)
    } catch (_: Exception) {
        OptionalPayload(payload = null, partial = true)
    }

    private fun freshMemoryEntry(
        snapshot: EncryptedAccountStore.AccountSnapshot,
    ): MemoryCacheEntry? = synchronized(memoryLock) {
        val entry = memoryCache[snapshot.id] ?: return@synchronized null
        val age = System.currentTimeMillis() - entry.storedAtMillis
        entry.takeIf { it.revision == snapshot.revision && age in 0 until MEMORY_TTL_MILLIS }
    }

    private fun freshTasksMemoryEntry(
        snapshot: EncryptedAccountStore.AccountSnapshot,
    ): TasksMemoryEntry? = synchronized(tasksMemoryLock) {
        val entry = tasksMemoryCache[snapshot.id] ?: return@synchronized null
        val age = System.currentTimeMillis() - entry.storedAtMillis
        entry.takeIf { it.revision == snapshot.revision && age in 0 until MEMORY_TTL_MILLIS }
    }

    private fun ensureCurrent(snapshot: EncryptedAccountStore.AccountSnapshot) {
        if (!accountStore.isCurrent(snapshot.id, snapshot.revision)) {
            throw AccountRevisionChangedException()
        }
    }

    private fun markCredentialInvalid(snapshot: EncryptedAccountStore.AccountSnapshot) {
        if (accountStore.isCurrent(snapshot.id, snapshot.revision)) {
            accountStore.markTokenInvalid(snapshot.id, snapshot.revision, invalid = true)
        }
    }

    private fun invalidateUsage(accountId: Int, revision: Long) {
        synchronized(memoryLock) {
            if (memoryCache[accountId]?.revision == revision) memoryCache.remove(accountId)
            if (parsedUsageCache[accountId]?.revision == revision) parsedUsageCache.remove(accountId)
        }
        usageCache.remove(accountId, revision)
    }

    private fun invalidateUsage(accountId: Int) {
        synchronized(memoryLock) {
            memoryCache.remove(accountId)
            parsedUsageCache.remove(accountId)
        }
        usageCache.remove(accountId)
    }

    private fun invalidateTasks(accountId: Int, revision: Long) {
        synchronized(tasksMemoryLock) {
            if (tasksMemoryCache[accountId]?.revision == revision) tasksMemoryCache.remove(accountId)
            if (parsedTasksCache[accountId]?.revision == revision) parsedTasksCache.remove(accountId)
        }
        tasksCache.remove(accountId, revision)
    }

    private fun invalidateTasks(accountId: Int) {
        synchronized(tasksMemoryLock) {
            tasksMemoryCache.remove(accountId)
            parsedTasksCache.remove(accountId)
        }
        tasksCache.remove(accountId)
    }

    private fun clearMigratedAccountUsageCaches() {
        val pendingAccountIds = accountStore.pendingUsageCacheCleanupAccountIds()
        val clearedAccountIds = pendingAccountIds.filter { accountId -> usageCache.remove(accountId) }
        if (clearedAccountIds.isNotEmpty()) {
            accountStore.acknowledgeUsageCacheCleanup(clearedAccountIds)
        }
    }

    private fun AgenTOverview.asCached(ageSeconds: Int, isLocal: Boolean): AgenTOverview = copy(
        fromCache = true,
        cacheAgeSeconds = ageSeconds,
        isLocalCache = isLocal,
    )

    private fun ageSeconds(storedAtMillis: Long): Int =
        ((System.currentTimeMillis() - storedAtMillis).coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private data class MemoryCacheEntry(
        val revision: Long,
        val storedAtMillis: Long,
        val overview: AgenTOverview,
    )

    private data class ParsedUsageCacheEntry(
        val revision: Long,
        val rawJson: String,
        val overview: AgenTOverview,
    )

    private data class TasksMemoryEntry(
        val revision: Long,
        val storedAtMillis: Long,
        val tasks: AgenTasks,
    )

    private data class ParsedTasksCacheEntry(
        val revision: Long,
        val rawJson: String,
        val tasks: AgenTasks,
    )

    private data class OptionalPayload(
        val payload: JSONObject?,
        val partial: Boolean,
    )

    private data class NetworkRequestKey(
        val accountId: Int,
        val revision: Long,
    )

    private companion object {
        const val MEMORY_TTL_MILLIS = 60_000L
        val memoryCache = mutableMapOf<Int, MemoryCacheEntry>()
        val parsedUsageCache = mutableMapOf<Int, ParsedUsageCacheEntry>()
        val memoryLock = Any()
        val tasksMemoryCache = mutableMapOf<Int, TasksMemoryEntry>()
        val parsedTasksCache = mutableMapOf<Int, ParsedTasksCacheEntry>()
        val tasksMemoryLock = Any()
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val activeUsageRequests = ConcurrentHashMap<
            NetworkRequestKey,
            CompletableDeferred<AgenTOverview>
        >()
        val activeTasksRequests = ConcurrentHashMap<
            NetworkRequestKey,
            CompletableDeferred<AgenTasks>
        >()
    }
}
