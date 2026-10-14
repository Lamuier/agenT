package com.lamuier.agenT.usage

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageHistoryStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = UsageHistoryStore(context, true,
        encode = { text, aad -> Base64.getEncoder().encodeToString("$aad|$text".toByteArray()) },
        decode = { text, aad -> String(Base64.getDecoder().decode(text)).also { check(it.startsWith("$aad|")) }.removePrefix("$aad|") })
    private val now = 1_800_000_000_000L
    private fun point(time: Long = now) = UsageObservation(time, mapOf("own" to 5.0), mapOf("own" to "cycle"), mapOf("own" to now + UsageAnalysis.DAY))
    @Before fun reset() { store().clear(); store().setEnabled(true) }
    @Test fun isolatesOwnersAndServicesAndUsesNoBackupDirectory() {
        val store = store(); val ticket = store.generation()
        store.record("cursor", "a", point(), ticket)
        store.record("codex", "b", point(), ticket)
        assertEquals(1, store.read("cursor", "a", now).size)
        assertTrue(store.read("cursor", "b", now).isEmpty())
        val file = File(context.noBackupFilesDir, "usage-history-qa").listFiles()!!.first()
        assertFalse(file.readText().contains("values"))
        store.clear("cursor")
        assertTrue(store.read("cursor", "a", now).isEmpty())
        assertEquals(1, store.read("codex", "b", now).size)
    }
    @Test fun clearingOrDisablingRejectsInFlightOldTicketsAndCachesDoNotDuplicate() {
        val store = store(); val ticket = store.generation()
        assertTrue(store.record("cursor", "a", point(), ticket))
        assertFalse(store.record("cursor", "a", point(), ticket))
        store.clear()
        assertFalse(store.record("cursor", "a", point(now + 1000), ticket))
        val next = store.generation(); store.setEnabled(false)
        assertFalse(store.record("cursor", "a", point(now + 1000), next))
        store.setEnabled(true)
        assertTrue(store.record("cursor", "a", point(now + 1000), store.generation()))
    }
    @Test fun prunesAfterNinetyDaysAndReportsCorruptData() {
        val store = store(); store.record("cursor", "a", point(now - 91 * UsageAnalysis.DAY), store.generation())
        assertTrue(store.read("cursor", "a", now).isEmpty())
        store.record("cursor", "a", point(), store.generation())
        File(context.noBackupFilesDir, "usage-history-qa").listFiles()!!.first().writeText("corrupt")
        assertTrue(runCatching { store.read("cursor", "a", now) }.isFailure)
    }
    @Test fun clockRollbackCannotAppendAnOlderDay() {
        val store = store(); val ticket = store.generation()
        store.record("cursor", "a", point(), ticket)
        assertFalse(store.record("cursor", "a", point(now - UsageAnalysis.DAY), ticket))
    }
    @Test fun concurrentInstancesDoNotLosePoints() {
        val ticket = store().generation()
        val threads = (0..10).map { i -> Thread { store().record("cursor", "a", point(now + i * 300_000), ticket) }.apply { start() } }
        threads.forEach { it.join() }
        val rows = store().read("cursor", "a", now + 3_000_000)
        assertTrue(rows.isNotEmpty()); assertEquals(now + 3_000_000, rows.last().atMillis)
        assertEquals(rows.size, rows.map { it.atMillis }.distinct().size)
    }

    @Test fun repeatedReadsReuseDaysAndOnlyChangedFilesAreDecodedAgain() {
        var decoded = 0
        val store = UsageHistoryStore(context, true,
            encode = { text, _ -> text }, decode = { text, _ -> decoded++; text })
        store.clear()
        val ticket = store.generation()
        repeat(3) { day -> store.record("cursor", "cache-test", point(now - (2 - day) * UsageAnalysis.DAY), ticket) }
        UsageHistoryStore.trimMemory()
        assertEquals(3, store.read("cursor", "cache-test", now).size)
        assertEquals(3, decoded)
        assertEquals(3, store.read("cursor", "cache-test", now).size)
        assertEquals(3, decoded)
        val file = File(context.noBackupFilesDir, "usage-history-qa").listFiles()!!.first()
        assertTrue(file.setLastModified(file.lastModified() + 2000))
        store.read("cursor", "cache-test", now)
        assertEquals(4, decoded)
        assertEquals(2, store.read("cursor", "cache-test", now, days = 1).size)
        store.clear("cursor")
        assertTrue(store.read("cursor", "cache-test", now).isEmpty())
    }
}
