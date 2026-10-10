package com.lamuier.agenT.model

import org.junit.Assert.*
import org.junit.Test

class TokenFormRulesTest {
    @Test fun newCursorNeedsAliasAndToken() {
        assertEquals(TokenFormError.AliasRequired, TokenFormRules.validate(TokenProvider.Cursor, null, " ", "test"))
        assertEquals(TokenFormError.TokenRequired, TokenFormRules.validate(TokenProvider.Cursor, null, "Personal", " "))
    }
    @Test fun cursorAliasLengthIsBounded() {
        assertEquals(TokenFormError.AliasTooLong, TokenFormRules.validate(TokenProvider.Cursor, null, "a".repeat(65), "test"))
        assertNull(TokenFormRules.validate(TokenProvider.Cursor, null, "a".repeat(64), "test"))
    }
    @Test fun savedCursorCanUpdateAliasWithoutReenteringToken() {
        assertNull(TokenFormRules.validate(TokenProvider.Cursor, "Old", " New ", " "))
    }
    @Test fun unchangedCursorFormDoesNotSaveOrRevealExistingSecret() {
        assertEquals(TokenFormError.NoChanges, TokenFormRules.validate(TokenProvider.Cursor, "Personal", " Personal ", " "))
    }
    @Test fun codexRequiresACompleteReplacementTokenEvenIfAlreadySaved() {
        assertEquals(TokenFormError.TokenRequired, TokenFormRules.validate(TokenProvider.Codex, "account", "", " "))
        assertEquals(TokenFormError.TokenTooShort, TokenFormRules.validate(TokenProvider.Codex, null, "", "short"))
    }
    @Test fun codexAccountIdStaysOptional() {
        assertNull(TokenFormRules.validate(TokenProvider.Codex, null, "", "x".repeat(32)))
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
