package com.lamuier.agenT.ui

import com.lamuier.agenT.model.TokenProvider
import org.junit.Assert.*
import org.junit.Test

class TokenDraftViewModelTest {
    @Test fun configurationReentryKeepsDraftButProviderAndRevisionChangesClearIt() {
        val draft = TokenDraftViewModel()
        draft.activate(TokenProvider.Cursor, "revision-1", "original")
        draft.metadata.value = "edited"
        draft.token.value = "fixture-token"
        draft.activate(TokenProvider.Cursor, "revision-1", "original")
        assertEquals("edited", draft.metadata.value)
        assertEquals("fixture-token", draft.token.value)
        draft.activate(TokenProvider.Codex, "revision-1", "account")
        assertEquals("", draft.token.value)
        draft.token.value = "fixture-token"
        draft.activate(TokenProvider.Codex, "revision-2", "account")
        assertEquals("", draft.token.value)
        draft.token.value = "fixture-token"
        draft.clear()
        assertEquals("", draft.token.value)
        assertEquals("", draft.metadata.value)
    }
}
