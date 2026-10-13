package com.lamuier.agenT.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.lamuier.agenT.model.TokenProvider

/** Survives configuration changes in RAM only; never use SavedStateHandle for credentials. */
internal class TokenDraftViewModel : ViewModel() {
    val metadata = mutableStateOf("")
    val token = mutableStateOf("")
    private var key: Pair<TokenProvider, String?>? = null

    fun activate(provider: TokenProvider, revision: String?, initialMetadata: String) {
        if (key != (provider to revision)) {
            clear()
            key = provider to revision
            metadata.value = initialMetadata
        }
    }
    fun clear() { token.value = ""; metadata.value = ""; key = null }
    override fun onCleared() { clear() }
}
