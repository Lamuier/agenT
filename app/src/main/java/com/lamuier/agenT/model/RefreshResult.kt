package com.lamuier.agenT.model

/** A fresh cache or a disabled automatic query is not a failed network refresh. */
enum class RefreshResult {
    Updated,
    Skipped,
    Failed;

    companion object {
        fun fromData(fromCache: Boolean, incomplete: Boolean = false): RefreshResult = when {
            incomplete -> Failed
            fromCache -> Skipped
            else -> Updated
        }
    }
}
