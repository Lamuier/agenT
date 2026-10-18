package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class UsageCacheStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val crypto by lazy { KeystoreCrypto(KEY_ALIAS) }

    fun read(accountId: Int, revision: Long): CacheEntry? {
        val storageKey = key(accountId, revision)
        val encrypted = preferences.getString(storageKey, null) ?: return null
        decryptedCache[storageKey]?.takeIf { it.first == encrypted }?.let { return it.second }
        return try {
            val wrapper = JSONObject(crypto.decrypt(encrypted, aad(accountId, revision)))
            if (wrapper.optInt("schema") != CACHE_SCHEMA) return null
            CacheEntry(
                rawJson = wrapper.getJSONObject("usage").toString(),
                storedAtMillis = wrapper.getLong("stored_at"),
            ).also {
                decryptedCache[storageKey] = encrypted to it
            }
        } catch (_: Exception) {
            synchronized(lock) {
                decryptedCache.remove(storageKey)
                preferences.edit().remove(storageKey).apply()
            }
            null
        }
    }

    fun write(accountId: Int, revision: Long, rawJson: String, storedAtMillis: Long): Boolean = synchronized(lock) {
        val prefix = prefix(accountId)
        val hasNewerRevision = preferences.all.keys.any { existingKey ->
            existingKey.startsWith(prefix) &&
                (existingKey.removePrefix(prefix).toLongOrNull() ?: Long.MIN_VALUE) > revision
        }
        if (hasNewerRevision) return@synchronized false
        val normalized = JSONObject(rawJson).toString()
        val wrapper = JSONObject()
            .put("schema", CACHE_SCHEMA)
            .put("stored_at", storedAtMillis)
            .put("usage", JSONObject(normalized))
        val encrypted = crypto.encrypt(wrapper.toString(), aad(accountId, revision))
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { oldKey ->
            decryptedCache.remove(oldKey)
            editor.remove(oldKey)
        }
        val targetKey = key(accountId, revision)
        val committed = editor.putString(targetKey, encrypted).commit()
        if (committed) {
            decryptedCache[targetKey] = encrypted to CacheEntry(rawJson = normalized, storedAtMillis = storedAtMillis)
        }
        committed
    }

    fun remove(accountId: Int): Boolean = synchronized(lock) {
        val prefix = prefix(accountId)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { oldKey ->
            decryptedCache.remove(oldKey)
            editor.remove(oldKey)
        }
        editor.commit()
    }

    fun remove(accountId: Int, revision: Long): Boolean = synchronized(lock) {
        val targetKey = key(accountId, revision)
        decryptedCache.remove(targetKey)
        preferences.edit().remove(targetKey).commit()
    }

    fun clear(): Boolean = synchronized(lock) {
        decryptedCache.clear()
        preferences.edit().clear().commit()
    }

    private fun key(accountId: Int, revision: Long): String = "usage_${accountId}_r$revision"

    private fun prefix(accountId: Int): String = "usage_${accountId}_r"

    private fun aad(accountId: Int, revision: Long): String =
        "${BuildConfig.APPLICATION_ID}|usage-cache-v2|$accountId|$revision"

    private companion object {
        const val CACHE_SCHEMA = 1
        const val PREFERENCES_NAME = "cursor_direct_usage_cache_v2"
        const val KEY_ALIAS = "cursor_direct_usage_cache_key_v2"
        val lock = Any()
        val decryptedCache = ConcurrentHashMap<String, Pair<String, CacheEntry>>()
    }

    data class CacheEntry(
        val rawJson: String,
        val storedAtMillis: Long,
    )
}
