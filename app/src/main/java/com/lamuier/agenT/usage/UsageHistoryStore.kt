package com.lamuier.agenT.usage

import android.content.Context
import android.util.AtomicFile
import com.lamuier.agenT.data.KeystoreCrypto
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

/** Daily encrypted files never enter backup/transfer. No raw token or server response is persisted. */
internal class UsageHistoryStore(
    context: Context,
    isolated: Boolean = false,
    private val encode: ((String, String) -> String)? = null,
    private val decode: ((String, String) -> String)? = null,
) {
    private val root = File(context.noBackupFilesDir, if (isolated) "usage-history-qa" else "usage-history-v1")
    private val prefs = context.getSharedPreferences(if (isolated) "usage-history-qa" else "usage-history-v1", Context.MODE_PRIVATE)
    private val crypto by lazy { KeystoreCrypto(if (isolated) "usage_history_qa" else "usage_history_v1") }
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun generation(): Long = synchronized(lock) { prefs.getLong("generation", 0) }
    fun setEnabled(value: Boolean) = synchronized(lock) {
        check(prefs.edit().putBoolean("enabled", value).putLong("generation", generation() + 1).commit())
        notifyChanged(null)
    }
    fun reportFailure(service: String, owner: String) = synchronized(lock) {
        failedOwners += "$service:$owner"
        notifyChanged(service)
    }
    fun clear(service: String? = null) = synchronized(lock) {
        check(prefs.edit().putLong("generation", generation() + 1).commit())
        root.listFiles()?.filter { service == null || it.name.startsWith("${service}_") }?.forEach { check(it.delete() || !it.exists()) }
        failedOwners.removeAll { service == null || it.startsWith("$service:") }
        decodedFiles.keys.removeAll { key -> key.startsWith(root.absolutePath + File.separator) &&
            (service == null || File(key).name.startsWith("${service}_")) }
        notifyChanged(service)
    }
    fun read(service: String, owner: String, now: Long = System.currentTimeMillis(), days: Int = 90): List<UsageObservation> = synchronized(lock) {
        require(days in 1..90)
        check("$service:$owner" !in failedOwners) { "History write failed" }
        val files = prune(now)
        val earliest = now - days * UsageAnalysis.DAY
        files.filter { it.name.startsWith("${service}_${owner}_") && it.name.endsWith(".json") &&
            it.name.substringBefore(".json").substringAfterLast('_').toLongOrNull()?.let { day -> day >= earliest / UsageAnalysis.DAY } == true }
            .sortedBy { it.name }.flatMap { readFile(it) }.filter { it.atMillis in earliest..now }.sortedBy { it.atMillis }
    }
    fun record(service: String, owner: String, point: UsageObservation, ticket: Long): Boolean = synchronized(lock) {
        if (!enabled() || ticket != generation() || point.values.isEmpty()) return@synchronized false
        root.mkdirs()
        val files = prune(point.atMillis)
        val latestDay = files.filter { it.name.startsWith("${service}_${owner}_") }
            .mapNotNull { it.name.substringBefore(".json").substringAfterLast('_').toLongOrNull() }.maxOrNull()
        if (latestDay != null && latestDay > point.atMillis / UsageAnalysis.DAY) return@synchronized false
        val file = File(root, "${service}_${owner}_${point.atMillis / UsageAnalysis.DAY}.json")
        val existing = if (file.isFile) readFile(file) else emptyList()
        if (existing.any { it.atMillis >= point.atMillis }) return@synchronized false
        val next = merge(existing, point)
        val plain = JSONObject().put("schema", 1).put("points", JSONArray().apply { next.forEach { put(toJson(it)) } }).toString()
        val encrypted = encode?.invoke(plain, file.name) ?: crypto.encrypt(plain, file.name)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(encrypted.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        failedOwners.remove("$service:$owner")
        rememberFile(file, next)
        notifyChanged(service)
        true
    }
    private fun readFile(file: File): List<UsageObservation> {
        decodedFiles[file.absolutePath]?.takeIf { it.modified == file.lastModified() && it.length == file.length() && file.isFile }
            ?.let { return it.points }
        check(file.length() <= 2 * 1024 * 1024) { "History file exceeds limit" }
        val encrypted = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        val plain = decode?.invoke(encrypted, file.name) ?: crypto.decrypt(encrypted, file.name)
        val json = JSONObject(plain); check(json.getInt("schema") == 1)
        val points = json.getJSONArray("points"); check(points.length() <= 1000)
        return (0 until points.length()).map { fromJson(points.getJSONObject(it)) }.also { rememberFile(file, it) }
    }
    private fun prune(now: Long): List<File> {
        val earliestDay = (now - 90 * UsageAnalysis.DAY) / UsageAnalysis.DAY
        val allFiles = root.listFiles()?.toList().orEmpty()
        val remaining = ArrayList<File>(allFiles.size)
        for (file in allFiles) {
            val expired = file.name.substringBefore(".json").substringAfterLast('_').toLongOrNull()?.let { it < earliestDay } == true
            if (expired) {
                check(file.delete() || !file.exists())
                decodedFiles.remove(file.absolutePath)
            } else {
                remaining.add(file)
            }
        }
        return remaining
    }
    private fun rememberFile(file: File, points: List<UsageObservation>) {
        decodedFiles[file.absolutePath] = DecodedDay(file.lastModified(), file.length(), points)
        // Ninety rolling days can span 91 UTC files for each of two services.
        while (decodedFiles.size > 182) decodedFiles.remove(decodedFiles.keys.first())
    }
    companion object {
        private data class DecodedDay(val modified: Long, val length: Long, val points: List<UsageObservation>)
        private val decodedFiles = LinkedHashMap<String, DecodedDay>(180, 0.75f, true)
        private val lock = Any()
        private val failedOwners = mutableSetOf<String>()
        private val changed = MutableStateFlow(0L)
        private val serviceChanges = MutableStateFlow(mapOf("cursor" to 0L, "codex" to 0L))
        val changes = changed.asStateFlow()
        fun changesFor(service: String) = serviceChanges.map { it[service] ?: 0L }.distinctUntilChanged()
        private fun notifyChanged(service: String?) {
            changed.value += 1
            serviceChanges.value = serviceChanges.value.mapValues { (key, value) -> if (service == null || service == key) value + 1 else value }
        }
        fun trimMemory() = synchronized(lock) { decodedFiles.clear() }
        fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun merge(existing: List<UsageObservation>, point: UsageObservation): List<UsageObservation> {
            val previous = existing.lastOrNull()
            val sameBucket = previous != null && previous.atMillis / 300_000 == point.atMillis / 300_000 &&
                previous.boundaries.keys == point.boundaries.keys && point.boundaries.keys.all { UsageAnalysis.sameBoundary(previous, point, it) } && point.values.all { (key, value) -> value >= (previous.values[key] ?: value) }
            return ((if (sameBucket) existing.dropLast(1) else existing) + point).takeLast(1000)
        }
        private fun toJson(p: UsageObservation): JSONObject = JSONObject().put("at", p.atMillis).put("values", JSONObject(p.values))
            .put("boundaries", JSONObject(p.boundaries)).put("resets", JSONObject(p.resetsAtMillis))
        private fun fromJson(j: JSONObject): UsageObservation {
            val values = j.getJSONObject("values"); val boundaries = j.getJSONObject("boundaries"); val resets = j.getJSONObject("resets")
            return UsageObservation(j.getLong("at"), values.keys().asSequence().mapNotNull { k -> values.optDouble(k).takeIf { it.isFinite() && it >= 0 }?.let { k to it } }.toMap(),
                boundaries.keys().asSequence().associateWith { boundaries.getString(it) }, resets.keys().asSequence().associateWith { resets.getLong(it) })
        }
    }
}
