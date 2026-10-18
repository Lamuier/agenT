package com.lamuier.agenT.providers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ServiceStatusRepository
import com.lamuier.agenT.model.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProvidersViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repositories = UsageProvider.entries.associateWith { ProviderUsageRepository(appContext, it) }
    private val statuses = UsageProvider.entries.mapNotNull { provider -> provider.statusProvider?.let {
        provider to ServiceStatusRepository(appContext, it)
    } }.toMap()
    private val mutableState = MutableStateFlow(ProvidersUiState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            val restored = coroutineScope {
                UsageProvider.entries.map { provider -> async(Dispatchers.IO) {
                    val cached = repositories.getValue(provider).cached()
                    provider to ProviderState(credential = cached.credential, usage = cached.usage,
                        serviceStatus = runCatching { statuses[provider]?.cached() }.getOrNull(),
                        error = cached.failure?.let { appContext.getString(it.messageFor(provider)) })
                } }.awaitAll().toMap()
            }
            mutableState.value = ProvidersUiState(booting = false, sources = restored)
            repositories.forEach { (provider, repository) -> launch {
                repository.changes.collect {
                    val cached = withContext(Dispatchers.IO) { repository.cached() }
                    update(provider) { withUsageSnapshot(cached, cached.failure?.let { appContext.getString(it.messageFor(provider)) }) }
                }
            } }
        }
    }
    fun clearError(provider: UsageProvider) = update(provider) { copy(error = null) }
    fun save(provider: UsageProvider, key: String, region: KimiRegion, done: () -> Unit) {
        if (state.value.booting || state.value[provider].saving) return
        val secret = if (provider == UsageProvider.Antigravity) try { AntigravityGrant.parse(key).encode() }
            catch (_: Exception) { update(provider) { copy(error = appContext.getString(R.string.antigravity_grant_invalid)) }; return }
            else key.trim()
        if (secret.isEmpty() || provider != UsageProvider.Antigravity && secret.any { it.isWhitespace() || it.isISOControl() }) {
            update(provider) { copy(error = appContext.getString(R.string.provider_key_invalid)) }; return
        }
        update(provider) { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val connection = withContext(Dispatchers.IO) { repositories.getValue(provider).save(secret, region) }
                update(provider) { copy(credential = connection, usage = null, error = null, saving = false, loading = false) }
                done()
                refresh(provider, true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update(provider) { copy(saving = false, error = appContext.getString(R.string.provider_storage_error)) } }
        }
    }
    fun delete(provider: UsageProvider, done: () -> Unit) {
        if (state.value[provider].saving) return
        update(provider) { copy(saving = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repositories.getValue(provider).clear() }
                update(provider) { copy(credential = null, usage = null, error = null, saving = false, loading = false) }
                done()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update(provider) { copy(saving = false, error = appContext.getString(R.string.provider_storage_error)) } }
        }
    }
    fun reveal(provider: UsageProvider): String = repositories.getValue(provider).reveal()
    fun refresh(provider: UsageProvider, force: Boolean = false, complete: (RefreshResult) -> Unit = {}) {
        val initial = state.value[provider]
        val credential = initial.credential
        if (state.value.booting || initial.loading || initial.saving || credential == null) { complete(RefreshResult.Skipped); return }
        update(provider) { copy(loading = true, error = null) }
        viewModelScope.launch {
            var result = RefreshResult.Failed
            try {
                val fetched = withContext(Dispatchers.IO) {
                    repositories.getValue(provider).refresh(force)
                }
                if (state.value[provider].credential == credential && !state.value[provider].saving && fetched.credential == credential) {
                    update(provider) { copy(usage = fetched.usage, error = fetched.failure?.let { appContext.getString(it.messageFor(provider)) }) }
                    result = if (fetched.updated) RefreshResult.Updated else if (fetched.failure != null) RefreshResult.Failed else RefreshResult.Skipped
                } else result = RefreshResult.Skipped
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (state.value[provider].credential == credential && !state.value[provider].saving) {
                    update(provider) { copy(error = appContext.getString(R.string.provider_usage_error), usage = usage?.copy(fromCache = true)) }
                } else result = RefreshResult.Skipped
            } finally {
                if (state.value[provider].credential == credential) update(provider) { copy(loading = false) }
                complete(result)
            }
        }
    }
    fun refreshStatus(provider: UsageProvider, force: Boolean = false, complete: (RefreshResult) -> Unit = {}) {
        if (provider.statusProvider == null || state.value.booting || state.value[provider].loadingStatus) { complete(RefreshResult.Skipped); return }
        update(provider) { copy(loadingStatus = true) }
        viewModelScope.launch {
            var result = RefreshResult.Failed
            try {
                val status = statuses.getValue(provider).fetch(force)
                update(provider) { copy(serviceStatus = status, statusError = null) }
                result = if (status.fromCache) RefreshResult.Skipped else RefreshResult.Updated
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update(provider) { copy(statusError = appContext.getString(R.string.provider_status_error),
                serviceStatus = serviceStatus?.copy(fromCache = true)) } }
            finally { update(provider) { copy(loadingStatus = false) }; complete(result) }
        }
    }
    private fun update(provider: UsageProvider, transform: ProviderState.() -> ProviderState) {
        mutableState.update { it.copy(sources = it.sources + (provider to it[provider].transform())) }
    }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ProvidersViewModel(context) as T
    }
}

/** A delayed disk read must neither restore a deleted connection nor relabel an already live result. */
internal fun ProviderState.withUsageSnapshot(snapshot: ProviderUsageSnapshot, message: String?): ProviderState {
    if (saving || loading || credential != snapshot.credential) return this
    val next = snapshot.usage?.let {
        if (usage?.fetchedAt == it.fetchedAt && usage.fromCache == false && snapshot.failure == null) it.copy(fromCache = false) else it
    }
    return copy(usage = next, error = message)
}
