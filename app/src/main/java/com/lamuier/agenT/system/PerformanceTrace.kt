package com.lamuier.agenT.system

import android.os.Trace

/** Fixed labels only: no credentials, account identity, payload or exception content. */
internal inline fun <T> performanceTrace(label: String, block: () -> T): T {
    Trace.beginSection(label)
    return try { block() } finally { Trace.endSection() }
}
