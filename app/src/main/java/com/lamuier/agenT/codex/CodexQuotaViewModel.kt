package com.lamuier.agenT.codex

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ServiceStatusRepository
import com.lamuier.agenT.network.OfficialStatusProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun refreshPublicResets(force: Boolean = false) {
        if (publicResetRequestInFlight) return
        publicResetRequestInFlight = true
        _state.update { it.copy(refreshingPublicResets = true, publicResetsError = null) }
        viewModelScope.launch {
            try {
                if (_state.value.publicResets == null) {
                    val cached = withContext(Dispatchers.IO) { publicResetRepository.cached() }
                    _state.update { it.copy(publicResets = cached) }
                }
                val snapshot = withContext(Dispatchers.IO) { publicResetRepository.fetch(force) }
                _state.update { it.copy(publicResets = snapshot) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.update { it.copy(publicResetsError = error.message ?: appContext.getString(R.string.reset_public_load_error),
                    publicResets = it.publicResets?.copy(fromCache = true)) }
            } finally {
                publicResetRequestInFlight = false
                _state.update { it.copy(refreshingPublicResets = false) }
            }
        }
    }

    init {
        bootstrap()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { statusRepository.cached() }
            _state.update { it.copy(serviceStatus = cached, loadingStatus = cached == null) }
            refreshStatus(force = false)
        }
    }

    fun refreshStatus(force: Boolean = true) {
        if (statusRequestInFlight) return
        statusRequestInFlight = true
        _state.update {
            it.copy(loadingStatus = it.serviceStatus == null, refreshingStatus = true, statusError = null)
        }
        viewModelScope.launch {
            try {
                val status = statusRepository.fetch(force)
                _state.update { it.copy(serviceStatus = status, statusError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(statusError = error.message ?: appContext.getString(R.string.error_load_status),
                    serviceStatus = it.serviceStatus?.copy(fromCache = true)) }
            } finally {
                statusRequestInFlight = false
                _state.update { it.copy(loadingStatus = false, refreshingStatus = false) }
            }
        }
    }

    fun refresh(force: Boolean = true) {
        if (_state.value.publicResets != null) refreshPublicResets(force)
        val credential = _state.value.credential ?: return
        if (requestInFlight) { if (force) refreshQueued = true; return }
        if ((credential.expired || credential.invalid) && !force) return
        val elapsedAge = lastSuccessfulElapsed?.let { ((android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val snapshot = _state.value.usage?.let { it.copy(cacheAgeSeconds = maxOf(it.cacheAgeSeconds, elapsedAge ?: 0)) }
        if (!force && !CodexRefreshPolicy.shouldFetch(snapshot, System.currentTimeMillis() / 1000, 60)) return
        requestInFlight = true
        val revision = repository.tokenRevision()
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val usage = withContext(Dispatchers.IO) { repository.fetchUsage(revision) }
                if (repository.tokenRevision() == revision) {
                    lastSuccessfulElapsed = android.os.SystemClock.elapsedRealtime()
                    _state.update {
                        it.copy(
                            credential = repository.credentialInfo(),
                            usage = usage,
                            error = null,
                        )
                    }
                    reloadResetHistory()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (repository.tokenRevision() == revision) {
                    _state.update {
                        it.copy(
                            credential = runCatching { repository.credentialInfo() }.getOrNull()
                                ?: it.credential,
                            error = messageFor(error),
                            usage = it.usage?.copy(fromCache = true),
                        )
                    }
                }
            } finally {
                requestInFlight = false
                _state.update { it.copy(refreshing = false, booting = false) }
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

    fun registerReset(event: CodexResetEvent, onSuccess: () -> Unit = {}) =
        mutateResetHistory({ revision -> repository.registerReset(revision, event) }, onSuccess)
    fun deleteReset(id: String) = mutateResetHistory({ revision -> repository.deleteReset(revision, id) })
    fun setSubscriptionDay(day: Int) = mutateResetHistory({ revision -> repository.setSubscriptionDay(revision, day) })

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
            val credential = withContext(Dispatchers.IO) {
                runCatching { repository.credentialInfo() }.getOrNull()
            }
            val revision = repository.tokenRevision()
            val cached = if (credential != null && !credential.expired) {
                withContext(Dispatchers.IO) { runCatching { repository.cachedUsage(revision) }.getOrNull() }
            } else {
                null
            }
            _state.update { it.copy(credential = credential, usage = cached, booting = false) }
            reloadResetHistory()
            if (credential != null && !credential.expired) refresh(force = false)
        }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is CodexQuotaApiException -> error.message
        is IllegalArgumentException -> error.message
            ?: appContext.getString(R.string.codex_error_save_token)
        is IOException -> error.message?.takeIf(String::isNotBlank)
            ?: appContext.getString(R.string.codex_error_network)
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
