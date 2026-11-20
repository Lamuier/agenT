package com.lamuier.agenT.model

enum class TokenProvider {
    Cursor, Codex, DeepSeek, KimiCode;

    companion object {
        fun initial(hasCursor: Boolean, hasCodex: Boolean, preferred: TokenProvider? = null): TokenProvider =
            preferred ?: if (!hasCursor && hasCodex) Codex else Cursor
    }
}

enum class TokenFormError { TokenRequired, TokenTooShort }

/** Shared form validation; provider repositories still perform authoritative credential validation. */
internal object TokenFormRules {
    fun validate(provider: TokenProvider, token: String): TokenFormError? {
        val secret = token.trim()
        if (secret.isEmpty()) return TokenFormError.TokenRequired
        if (provider == TokenProvider.Codex && secret.length < 32) return TokenFormError.TokenTooShort
        return null
    }
}
