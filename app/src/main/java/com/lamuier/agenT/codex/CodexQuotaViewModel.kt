package com.lamuier.agenT.codex

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lamuier.agenT.R
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.data.ServiceStatusRepository
import com.lamuier.agenT.model.RefreshResult
import com.lamuier.agenT.network.OfficialStatusProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.lamuier.agenT.system.performanceTrace

class CodexQuotaViewModel(
    private val repository: CodexQuotaRepository,
    private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(CodexQuotaUiState())
    val state: StateFlow<CodexQuotaUiState> = _state.asStateFlow()
    private var requestInFlight = false
    private var refreshQueued = false
    private var lastSuccessfulElapsed: Long? = null
    private val statusRepository = ServiceStatusRepository(appContext, OfficialStatusProvider.Codex)
    private var statusRequestInFlight = false
    private val publicResetRepository = CodexPublicResetRepository(appContext)
    private var publicResetRequestInFlight = false

    fun refreshPublicResets(force: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        if (publicResetRequestInFlight) { onResult?.invoke(false); return }
        publicResetRequestInFlight = true
        _state.update { it.copy(refreshingPublicResets = true, publicResetsError = null) }
        viewModelScope.launch {
            var succeeded = false
            try {
                if (_state.value.publicResets == null) {
                    val cached = withContext(Dispatchers.IO) { publicResetRepository.cached() }
                    _state.update { it.copy(publicResets = cached) }
                }
                val snapshot = withContext(Dispatchers.IO) { publicResetRepository.fetch(force) }
                _state.update { it.copy(publicResets = snapshot) }
                succeeded = !snapshot.fromCache
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.update { it.copy(publicResetsError = error.message ?: appContext.getString(R.string.reset_public_load_error),
                    publicResets = it.publicResets?.copy(fromCache = true)) }
            } finally {
                onResult?.invoke(succeeded)
                publicResetRequestInFlight = false
                _state.update { it.copy(refreshingPublicResets = false) }
            }
        }
    }

    private var historyJob: Job? = null
    private val historyDays = MutableStateFlow(1)
    fun setHistoryDays(days: Int) { historyDays.value = days.coerceIn(1, 90) }

    fun setHistoryActive(active: Boolean) {
        if (!active) { historyJob?.cancel(); historyJob = null; return }
        if (historyJob != null) return
        historyJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _state.map { it.credential }.distinctUntilChanged(),
                com.lamuier.agenT.usage.UsageHistoryStore.changesFor("codex"),
                historyDays,
            ) { owner, _, days -> owner to days }.collectLatest { (owner, days) ->
                val rows = withContext(Dispatchers.IO) { runCatching { if (owner == null) emptyList() else repository.usageHistory(days) } }
                if (_state.value.credential == owner && historyDays.value == days) _state.update {
                    it.copy(usageHistory = rows.getOrDefault(emptyList()), usageHistoryError = rows.isFailure)
                }
            }
        }

    }

    init { bootstrap() }

    fun refreshStatus(force: Boolean = true, onResult: ((RefreshResult) -> Unit)? = null) {
        if (statusRequestInFlight) { onResult?.invoke(RefreshResult.Failed); return }
        statusRequestInFlight = true
        _state.update {
            it.copy(loadingStatus = it.serviceStatus == null, refreshingStatus = true, statusError = null)
        }
        viewModelScope.launch {
            var result = RefreshResult.Failed
            try {
                val status = statusRepository.fetch(force)
                _state.update { it.copy(serviceStatus = status, statusError = null) }
                result = RefreshResult.fromData(status.fromCache, status.partialComponents || status.partialHistory)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(statusError = error.message ?: appContext.getString(R.string.error_load_status),
                    serviceStatus = it.serviceStatus?.copy(fromCache = true)) }
            } finally {
                statusRequestInFlight = false
                _state.update { it.copy(loadingStatus = false, refreshingStatus = false) }
                onResult?.invoke(result)
            }
        }
    }

    fun refresh(force: Boolean = true, onResult: ((RefreshResult) -> Unit)? = null, includePublicResets: Boolean = true) {
        if (includePublicResets && _state.value.publicResets != null) refreshPublicResets(force)
        if (!force && !DashboardPreferences.get(appContext).readCodexUsageAutoRefresh()) { onResult?.invoke(RefreshResult.Skipped); return }
        val credential = _state.value.credential ?: run { onResult?.invoke(RefreshResult.Skipped); return }
        if (requestInFlight) { if (force) refreshQueued = true; onResult?.invoke(RefreshResult.Failed); return }
        if ((credential.expired || credential.invalid) && !force) { onResult?.invoke(RefreshResult.Skipped); return }
        val elapsedAge = lastSuccessfulElapsed?.let { ((android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val snapshot = _state.value.usage?.let { it.copy(cacheAgeSeconds = maxOf(it.cacheAgeSeconds, elapsedAge ?: 0)) }
        if (!force && !CodexRefreshPolicy.shouldFetch(snapshot, System.currentTimeMillis() / 1000, 60)) { onResult?.invoke(RefreshResult.Skipped); return }
        val revision = try {
            repository.tokenRevision()
        } catch (error: Exception) {
            _state.update { it.copy(error = messageFor(error)) }
            onResult?.invoke(RefreshResult.Failed)
            return
        }
        requestInFlight = true
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            var result = RefreshResult.Failed
            try {
                val usage = withContext(Dispatchers.IO) { repository.fetchUsage(revision) }
                if (repository.tokenRevision() == revision) {
                    val credential = withContext(Dispatchers.IO) { repository.credentialInfo() }
                    lastSuccessfulElapsed = android.os.SystemClock.elapsedRealtime()
                    _state.update {
                        it.copy(
                            credential = credential,
                            usage = usage,
                            error = null,
                        )
                    }
                    reloadResetHistory()
                    result = if (repository.tokenRevision() == revision) RefreshResult.fromData(usage.fromCache) else RefreshResult.Failed
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (repository.tokenRevision() == revision) {
                    val credential = withContext(Dispatchers.IO) { runCatching { repository.credentialInfo() }.getOrNull() }
                    _state.update {
                        it.copy(
                            credential = credential ?: it.credential,
                            error = messageFor(error),
                            usage = it.usage?.copy(fromCache = true),
                        )
                    }
                }
            } finally {
                requestInFlight = false
                _state.update { it.copy(refreshing = false, booting = false) }
                onResult?.invoke(result)
                if (refreshQueued) {
                    refreshQueued = false
                    if (_state.value.credential != null) refresh(force = true)
                }
            }
        }
    }

    fun saveCredential(
        accessToken: String,
        accountId: String,
        onSuccess: () -> Unit = {},
    ) {
        if (_state.value.saving || _state.value.resetSaving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val credential = withContext(Dispatchers.IO) {
                    repository.saveCredential(accessToken, accountId)
                }
                _state.update {
                    it.copy(
                        credential = credential,
                        usage = null,
                        // The new credential must not inherit the old snapshot's freshness.
                        resetLedger = null,
                        resetError = null,
                        saving = false,
                        booting = false,
                    )
                }
                onSuccess()
                lastSuccessfulElapsed = null
                reloadResetHistory()
                refresh(force = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(saving = false, booting = false, error = messageFor(error)) }
            }
        }
    }

    fun clearCredential(onSuccess: () -> Unit = {}) {
        if (_state.value.saving || _state.value.resetSaving) return
        _state.update { it.copy(saving = true, error = null) }
        refreshQueued = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.clearCredential() }
                lastSuccessfulElapsed = null
                _state.update {
                    it.copy(credential = null, usage = null, booting = false, saving = false, error = null,
                        resetLedger = null, resetError = null)
                }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(saving = false, error = messageFor(error)) }
            }
        }
    }

    fun revealToken(): String = repository.revealToken()
    fun nextRefreshDelayMillis(): Long = CodexRefreshPolicy.foregroundDelay(_state.value.usage, System.currentTimeMillis() / 1000)

    fun setSubscriptionDay(day: Int, onSuccess: () -> Unit = {}) = mutateResetHistory({ revision -> repository.setSubscriptionDay(revision, day) }, onSuccess)

    private fun mutateResetHistory(change: (Long) -> CodexResetLedger, done: () -> Unit = {}) {
        if (_state.value.saving || _state.value.resetSaving || _state.value.credential == null) return
        val revision = repository.tokenRevision()
        _state.update { it.copy(resetSaving = true, resetError = null) }
        viewModelScope.launch {
            try {
                val ledger = withContext(Dispatchers.IO) { change(revision) }
                if (repository.tokenRevision() == revision) {
                    _state.update { it.copy(resetLedger = ledger, resetSaving = false) }
                    done()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.update { it.copy(resetError = appContext.getString(R.string.reset_error_storage)) }
            } finally { _state.update { it.copy(resetSaving = false) } }
        }
    }

    private suspend fun reloadResetHistory() {
        val revision = repository.tokenRevision()
        try {
            val ledger = withContext(Dispatchers.IO) { repository.resetHistory() }
            if (repository.tokenRevision() == revision) _state.update {
                it.copy(resetLedger = ledger, resetError = repository.resetTrackingError)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { _state.update { it.copy(resetError = appContext.getString(R.string.reset_error_storage)) } }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { coroutineScope {
                    val status = async { runCatching { statusRepository.cached() }.getOrNull() }
                    val public = async { publicResetRepository.cached() }
                    val account = performanceTrace("agenT.restore.codex") {
                    val credential = runCatching { repository.credentialInfo() }.getOrNull()
                    val revision = repository.tokenRevision()
                    val cached = if (credential != null && !credential.expired) {
                        runCatching { repository.cachedUsage(revision) }.getOrNull()
                    } else {
                        null
                    }
                    credential to cached
                    }
                    Triple(account, status.await(), public.await())
                } }
                _state.update { it.copy(credential = restored.first.first, usage = restored.first.second,
                    serviceStatus = restored.second, publicResets = restored.third, loadingStatus = false, booting = false) }
                reloadResetHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 本地恢复失败也要进入可操作页面，不能一直停留在系统启动画面。
                _state.update { it.copy(booting = false, loadingStatus = false, error = messageFor(error)) }
            }
        }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is CodexQuotaApiException -> error.message
        is IllegalArgumentException -> error.message
            ?: appContext.getString(R.string.codex_error_save_token)
        is IOException -> appContext.getString(R.string.codex_error_network)
        else -> error.message?.takeIf(String::isNotBlank)
            ?: appContext.getString(R.string.codex_error_load_usage)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CodexQuotaViewModel::class.java))
            return CodexQuotaViewModel(CodexQuotaRepository(appContext), appContext) as T
        }
    }
}
