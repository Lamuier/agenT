package com.lamuier.agenT.qa

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.data.*
import com.lamuier.agenT.network.*
import com.lamuier.agenT.usage.*
import com.lamuier.agenT.util.TokenUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Synthetic local data, compiled only into profiling APKs. Never accepts real credentials. */
class PerformanceFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "fixture-preparing" }
        setContentView(label)
        Thread {
            val result = runCatching { seed(intent.getStringExtra("scenario") == "cached") }
            runOnUiThread { label.text = if (result.isSuccess) "fixture-ready" else "fixture-failed" }
        }.start()
    }

    private fun seed(cached: Boolean) {
        val accountStore = EncryptedAccountStore(this)
        accountStore.listAccounts().forEach { accountStore.delete(it.id) }
        UsageCacheStore(this).clear(); TasksCacheStore(this).clear()
        val codex = CodexQuotaRepository(this)
        codex.clearCredential()
        UsageHistoryStore(this).clear()
        DashboardPreferences.get(this).setCodexUsageAutoRefresh(false)
        ThemePreferences.get(this).update(ThemeSettings())
        getSharedPreferences("codex_public_resets_v1", 0).edit().clear().commit()
        StatusCacheStore(this).clear(); StatusCacheStore(this, OfficialStatusProvider.Codex.cacheName).clear()
        if (!cached) return
        val now = Instant.now(); val millis = now.toEpochMilli()
        val fixture = ReviewFixture.create(ZoneId.systemDefault(), now)
        val payload = "{\"sub\":\"performance-synthetic-only\",\"exp\":${now.epochSecond + 86400}}"
        val token = "eyJhbGciOiJub25lIn0.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.performance_only_invalid_signature"
        val account = accountStore.create("Performance fixture", token)
        accountStore.saveSelectedAccountId(account.id)
        val usage = requireNotNull(fixture.cursor.usage).copy(accountId = account.id, alias = account.alias)
        check(UsageCacheStore(this).write(account.id, accountStore.revision(account.id), UsageJsonParser.toJson(usage), millis))
        val tasks = requireNotNull(fixture.cursor.tasks).copy(accountId = account.id,
            tasks = (0 until 300).map { i -> fixture.cursor.tasks.tasks[i % 3].copy(id = "perf-$i", name = "Performance task $i") })
        check(TasksCacheStore(this).write(account.id, accountStore.revision(account.id), TasksJsonParser.toJson(tasks), millis))
        codex.saveCredential(token, "performance-synthetic-only")
        val revision = codex.tokenRevision()
        val wrapper = JSONObject().put("payload", CodexQuotaParser.toCache(requireNotNull(fixture.codex.usage))).put("stored_at", millis)
        val encrypted = CodexQuotaCrypto("agentt_codex_cache_key_v1").encrypt(wrapper.toString(),
            "$packageName|agentt-codex-cache-v1|$revision")
        check(getSharedPreferences("agentt_codex_cache_v1", 0).edit().putString("usage_r$revision", encrypted).commit())
        check(getSharedPreferences("codex_public_resets_v1", 0).edit().putString("snapshot",
            CodexPublicResetJson.encode(requireNotNull(fixture.codex.publicResets))).commit())
        for (provider in OfficialStatusProvider.entries) {
            val status = if (provider == OfficialStatusProvider.Cursor) fixture.cursor.serviceStatus!! else fixture.codex.serviceStatus!!
            val summary = JSONObject().put("page", JSONObject().put("url", provider.baseUrl).put("updated_at", now.toString()))
                .put("status", JSONObject().put("indicator", "none").put("description", "Operational"))
                .put("components", JSONArray().apply { status.components.forEach { c -> put(JSONObject()
                    .put("id", c.id).put("name", c.name).put("status", "operational").put("position", c.position)) } })
            check(StatusCacheStore(this, provider.cacheName).write(StatusJsonParser.toCacheJson(summary,
                JSONObject().put("incidents", JSONArray()), status.fetchedAt, provider.baseUrl, false), millis))
        }
        val owners = listOf("cursor" to UsageHistoryStore.identity("${account.id}:${TokenUtils.userId(token).ifBlank { token }}"),
            "codex" to CodexResetIdentity.key(token, "performance-synthetic-only"))
        val root = File(noBackupFilesDir, "usage-history-v1").apply { mkdirs() }
        val crypto = KeystoreCrypto("usage_history_v1")
        for ((service, owner) in owners) for (offset in 0 until 90) {
            val day = millis / UsageAnalysis.DAY - offset
            val points = JSONArray()
            for (i in 0 until 288) {
                val at = day * UsageAnalysis.DAY + i * 300_000L
                if (at > millis) break
                val source = if (service == "cursor") UsageAnalysis.cursor(usage, at) else UsageAnalysis.codex(fixture.codex.usage!!, at)
                points.put(JSONObject().put("at", at).put("values", JSONObject(source.values.mapValues { it.value * i / 288.0 }))
                    .put("boundaries", JSONObject(source.boundaries)).put("resets", JSONObject(source.resetsAtMillis)))
            }
            val file = File(root, "${service}_${owner}_$day.json")
            file.writeText(crypto.encrypt(JSONObject().put("schema", 1).put("points", points).toString(), file.name))
        }
    }
}
