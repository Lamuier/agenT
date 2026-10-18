package com.lamuier.agenT.util

/** Fixed, documented destinations. No account credentials or user-supplied URLs. */
object AntigravityLinks {
    const val REMOTE_CONTROL = "https://antigravity.google.com/"
    const val REMOTE_CONTROL_HELP = "https://www.antigravity.google/docs/remote-control/"
    const val USAGE_HELP = "https://www.antigravity.google/docs/cli/commands/usage"
    const val SUPPORT = "https://antigravity.google/support"

    fun isAllowed(url: String): Boolean = url in setOf(
        REMOTE_CONTROL, REMOTE_CONTROL_HELP, USAGE_HELP, SUPPORT,
    )
}
