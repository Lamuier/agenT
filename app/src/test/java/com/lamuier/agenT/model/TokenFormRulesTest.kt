package com.lamuier.agenT.model

import org.junit.Assert.*
import org.junit.Test

class TokenFormRulesTest {
    @Test fun everyProviderRequiresAToken() {
        TokenProvider.entries.forEach { provider ->
            assertEquals(TokenFormError.TokenRequired, TokenFormRules.validate(provider, " "))
        }
    }
    @Test fun cursorAcceptsATokenWithoutAnAlias() {
        assertNull(TokenFormRules.validate(TokenProvider.Cursor, "test"))
    }
    @Test fun codexRequiresACompleteReplacementToken() {
        assertEquals(TokenFormError.TokenTooShort, TokenFormRules.validate(TokenProvider.Codex, "short"))
        assertNull(TokenFormRules.validate(TokenProvider.Codex, "x".repeat(32)))
    }
    @Test fun unifiedEntryPrefersAvailableProviderWithoutHidingOtherProvider() {
        assertEquals(TokenProvider.Codex, TokenProvider.initial(false, true))
        assertEquals(TokenProvider.Cursor, TokenProvider.initial(true, false))
        assertEquals(TokenProvider.Cursor, TokenProvider.initial(true, true))
        assertEquals(TokenProvider.Cursor, TokenProvider.initial(false, false))
    }
    @Test fun providerSpecificConnectionPromptsStillUseTheUnifiedPanelWithCorrectTab() {
        assertEquals(TokenProvider.Codex, TokenProvider.initial(true, false, TokenProvider.Codex))
        assertEquals(TokenProvider.Cursor, TokenProvider.initial(false, true, TokenProvider.Cursor))
    }
}
