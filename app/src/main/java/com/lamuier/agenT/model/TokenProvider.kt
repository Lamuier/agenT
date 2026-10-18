package com.lamuier.agenT.model

enum class TokenProvider {
    Cursor, Codex, DeepSeek, KimiCode, Antigravity;

    companion object {
        fun initial(hasCursor: Boolean, hasCodex: Boolean, preferred: TokenProvider? = null): TokenProvider =
            preferred ?: if (!hasCursor && hasCodex) Codex else Cursor
    }
}

enum class TokenFormError { AliasRequired, AliasTooLong, TokenRequired, TokenTooShort, NoChanges }

/** Shared form validation; provider repositories still perform authoritative credential validation. */
internal object TokenFormRules {
    fun validate(provider: TokenProvider, existingMetadata: String?, metadata: String, token: String): TokenFormError? {
        val name = metadata.trim()
        val secret = token.trim()
        if (provider == TokenProvider.Cursor) {
            if (name.isEmpty()) return TokenFormError.AliasRequired
            if (name.length > 64) return TokenFormError.AliasTooLong
            if (existingMetadata == null && secret.isEmpty()) return TokenFormError.TokenRequired
            if (existingMetadata != null && name == existingMetadata && secret.isEmpty()) return TokenFormError.NoChanges
        } else {
            if (secret.isEmpty()) return TokenFormError.TokenRequired
            if (provider == TokenProvider.Codex && secret.length < 32) return TokenFormError.TokenTooShort
        }
        return null
    }
}
