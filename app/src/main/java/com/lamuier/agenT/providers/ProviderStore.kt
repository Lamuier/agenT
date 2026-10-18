package com.lamuier.agenT.providers

import android.content.Context
import com.lamuier.agenT.data.KeystoreCrypto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal data class ProviderConnection(val info: ProviderCredentialInfo, val key: String,
    val rawUsage: JSONObject? = null, val fetchedAt: String? = null, val failure: ProviderFailure? = null) {
    override fun toString() = "ProviderConnection(info=$info, key=[redacted])"
}

/** Credentials and financial cache share one encrypted, revision-bound record per source. */
internal interface ProviderCipher {
    fun encrypt(value: String, aad: String): String
    fun decrypt(value: String, aad: String): String
}
private class AndroidProviderCipher(provider: UsageProvider) : ProviderCipher {
    private val crypto = KeystoreCrypto("agenT.official.${provider.id}.v1")
    override fun encrypt(value: String, aad: String) = crypto.encrypt(value, aad)
    override fun decrypt(value: String, aad: String) = crypto.decrypt(value, aad)
}
internal class ProviderStore(context: Context, private val provider: UsageProvider,
    private val crypto: ProviderCipher = AndroidProviderCipher(provider)) {
    private val preferences = context.getSharedPreferences("official_usage_${provider.id}_v1", Context.MODE_PRIVATE)
    private val aad = "official-usage:${provider.id}:v1"
    fun read(): ProviderConnection? = synchronized(provider) {
        val encrypted = preferences.getString("record", null) ?: run {
            cachedConnections.remove(provider)
            return@synchronized null
        }
        cachedConnections[provider]?.takeIf { it.first == encrypted }?.let { return@synchronized it.second }
        val json = JSONObject(crypto.decrypt(encrypted, aad))
        require(json.getString("provider") == provider.id && json.getInt("schema") == 1)
        ProviderConnection(
            ProviderCredentialInfo(json.getString("revision"), KimiRegion.fromId(json.optString("region"))),
            json.getString("key"),
            json.optJSONObject("usage"),
            json.optString("fetchedAt").takeIf { it.isNotBlank() },
            ProviderFailure.entries.firstOrNull { it.name == json.optString("failure") },
        ).also { cachedConnections[provider] = encrypted to it }
    }
    fun save(key: String, region: KimiRegion): ProviderConnection = synchronized(provider) {
        val connection = ProviderConnection(ProviderCredentialInfo(UUID.randomUUID().toString(), if (provider == UsageProvider.DeepSeek) KimiRegion.China else region), key)
        write(connection)
        connection
    }
    fun cache(revision: String, usage: JSONObject, fetchedAt: String): Boolean = synchronized(provider) {
        val current = read()?.takeIf { it.info.revision == revision } ?: return@synchronized false
        write(current.copy(rawUsage = usage, fetchedAt = fetchedAt, failure = null))
        true
    }
    fun updateSecret(revision: String, secret: String): Boolean = synchronized(provider) {
        val current = read()?.takeIf { it.info.revision == revision } ?: return@synchronized false
        if (current.key != secret) write(current.copy(key = secret))
        true
    }
    fun recordFailure(revision: String, failure: ProviderFailure): Boolean = synchronized(provider) {
        val current = read()?.takeIf { it.info.revision == revision } ?: return@synchronized false
        write(current.copy(failure = failure))
        true
    }
    fun clear() = synchronized(provider) {
        cachedConnections.remove(provider)
        check(preferences.edit().clear().commit())
    }
    private fun write(connection: ProviderConnection) {
        val json = JSONObject().put("schema", 1).put("provider", provider.id).put("revision", connection.info.revision)
            .put("region", connection.info.region.id).put("key", connection.key)
            .put("usage", connection.rawUsage).put("fetchedAt", connection.fetchedAt).put("failure", connection.failure?.name)
        val encrypted = crypto.encrypt(json.toString(), aad)
        check(preferences.edit().putString("record", encrypted).commit())
        cachedConnections[provider] = encrypted to connection
    }

    private companion object {
        val cachedConnections = ConcurrentHashMap<UsageProvider, Pair<String, ProviderConnection>>()
    }
}
