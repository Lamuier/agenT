package com.lamuier.agenT.data

import android.content.Context
import androidx.annotation.StringRes
import com.lamuier.agenT.BuildConfig
import com.lamuier.agenT.R
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.TokenUtils
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EncryptedAccountStore(
    context: Context,
    private val preferencesName: String = PREFERENCES_NAME,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val crypto by lazy { KeystoreCrypto(keyAlias) }

    init {
        // 界面、小组件和通知各自创建存储对象。锁必须进程内共享，
        // 否则并发的读-改-写会用旧账号 JSON 盖掉新 Token。
        if (preferencesName == PREFERENCES_NAME) {
            migrateFromPulseClient(appContext)
            migrateToSingleAccount()
        }
    }

    private fun msg(@StringRes id: Int) = AppLocale.string(appContext, id)

    fun listAccounts(): List<CursorAccount> = synchronized(lock) {
        readStoredAccounts().map(::publicView)
    }

    fun create(alias: String, accessToken: String): CursorAccount = synchronized(lock) {
        val accounts = readStoredAccounts().toMutableList()
        require(accounts.isEmpty()) { msg(R.string.error_single_account_only) }
        val normalizedAlias = normalizeAlias(alias)
        require(accounts.none { decryptAlias(it) == normalizedAlias }) { msg(R.string.error_alias_exists) }
        val normalizedToken = TokenUtils.requireValidAccessToken(accessToken, AppLocale.wrap(appContext).resources)
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val minimumNextId = (accounts.maxOfOrNull { it.id } ?: 0) + 1
        val nextId = maxOf(preferences.getInt(KEY_NEXT_ID, minimumNextId), minimumNextId)
        val account = StoredAccount(
            id = nextId,
            uuid = uuid,
            encryptedAlias = crypto.encrypt(normalizedAlias, aad(uuid, "alias")),
            encryptedAccessToken = crypto.encrypt(normalizedToken, aad(uuid, "access_token")),
            tokenInvalid = false,
            revision = 1L,
            createdAt = now,
            updatedAt = now,
        )
        accounts += account
        saveStoredAccounts(accounts, nextId = nextId + 1)
        publicView(account)
    }

    fun update(accountId: Int, alias: String?, accessToken: String?): CursorAccount = synchronized(lock) {
        val accounts = readStoredAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.id == accountId }
        require(index >= 0) { msg(R.string.error_account_not_found) }
        val current = accounts[index]
        require(alias != null || accessToken != null) { msg(R.string.error_alias_or_token) }
        val nextAlias = alias?.let(::normalizeAlias)
        if (nextAlias != null) {
            require(
                accounts.none { it.id != accountId && decryptAlias(it) == nextAlias },
            ) { msg(R.string.error_alias_exists) }
        }
        val nextToken = accessToken?.let { TokenUtils.requireValidAccessToken(it, AppLocale.wrap(appContext).resources) }
        val updated = current.copy(
            encryptedAlias = nextAlias
                ?.let { crypto.encrypt(it, aad(current.uuid, "alias")) }
                ?: current.encryptedAlias,
            encryptedAccessToken = nextToken
                ?.let { crypto.encrypt(it, aad(current.uuid, "access_token")) }
                ?: current.encryptedAccessToken,
            tokenInvalid = if (nextToken != null) false else current.tokenInvalid,
            revision = current.revision + 1L,
            updatedAt = System.currentTimeMillis(),
        )
        accounts[index] = updated
        saveStoredAccounts(accounts)
        publicView(updated)
    }

    fun delete(accountId: Int): Boolean = synchronized(lock) {
        val accounts = readStoredAccounts()
        val remaining = accounts.filterNot { it.id == accountId }
        if (remaining.size == accounts.size) return@synchronized false
        val serialized = serializeAccounts(remaining).toString()
        val editor = preferences.edit().putString(KEY_ACCOUNTS, serialized)
        if (preferences.getInt(KEY_SELECTED_ACCOUNT, -1) == accountId) {
            editor.remove(KEY_SELECTED_ACCOUNT)
        }
        check(editor.commit()) { msg(R.string.error_delete_account_secure) }
        storedAccountsCache[preferencesName] = serialized to remaining
        retainCachedDecryptions(remaining)
        true
    }

    internal fun snapshot(accountId: Int): AccountSnapshot = synchronized(lock) {
        val account = readStoredAccounts().firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException(msg(R.string.error_account_not_found))
        AccountSnapshot(
            id = account.id,
            alias = decryptAlias(account),
            accessToken = decryptAccessToken(account),
            revision = account.revision,
            tokenInvalid = account.tokenInvalid,
        )
    }

    fun revision(accountId: Int): Long = synchronized(lock) {
        readStoredAccounts().firstOrNull { it.id == accountId }?.revision
            ?: throw IllegalArgumentException(msg(R.string.error_account_not_found))
    }

    fun revealAccessToken(accountId: Int): String = synchronized(lock) {
        val account = readStoredAccounts().firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException(msg(R.string.error_account_not_found))
        decryptAccessToken(account)
    }

    internal fun <T> withCurrentRevision(accountId: Int, revision: Long, action: () -> T): T? = synchronized(lock) {
        if (!isCurrent(accountId, revision)) null else action()
    }

    internal fun isCurrent(accountId: Int, revision: Long): Boolean = synchronized(lock) {
        readStoredAccounts().any { it.id == accountId && it.revision == revision }
    }

    fun selectedAccountId(): Int? = synchronized(lock) {
        preferences.getInt(KEY_SELECTED_ACCOUNT, -1).takeIf { it > 0 }
    }

    fun saveSelectedAccountId(accountId: Int?) = synchronized(lock) {
        if (selectedAccountId() == accountId) return@synchronized
        val editor = preferences.edit()
        if (accountId == null) editor.remove(KEY_SELECTED_ACCOUNT) else editor.putInt(KEY_SELECTED_ACCOUNT, accountId)
        check(editor.commit()) { msg(R.string.error_save_selection) }
    }

    internal fun pendingUsageCacheCleanupAccountIds(): List<Int> = synchronized(lock) {
        readPendingUsageCacheCleanupAccountIds()
    }

    internal fun acknowledgeUsageCacheCleanup(accountIds: Collection<Int>): Boolean = synchronized(lock) {
        if (accountIds.isEmpty()) return@synchronized true
        val clearedIds = accountIds.toSet()
        val remainingIds = readPendingUsageCacheCleanupAccountIds().filterNot(clearedIds::contains)
        val editor = preferences.edit()
        if (remainingIds.isEmpty()) {
            editor.remove(KEY_PENDING_USAGE_CACHE_CLEANUP)
        } else {
            editor.putString(KEY_PENDING_USAGE_CACHE_CLEANUP, serializeAccountIds(remainingIds).toString())
        }
        check(editor.commit()) { msg(R.string.error_save_account_secure) }
        true
    }

    internal fun markTokenInvalid(accountId: Int, revision: Long, invalid: Boolean): Boolean = synchronized(lock) {
        val accounts = readStoredAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.id == accountId }
        if (index < 0 || accounts[index].revision != revision) return@synchronized false
        if (accounts[index].tokenInvalid == invalid) return@synchronized true
        accounts[index] = accounts[index].copy(tokenInvalid = invalid)
        saveStoredAccounts(accounts)
        true
    }

    private fun publicView(account: StoredAccount): CursorAccount {
        val alias = decryptAlias(account)
        val expired = account.tokenInvalid || try {
            TokenUtils.isExpired(decryptAccessToken(account))
        } catch (_: Exception) {
            true
        }
        return CursorAccount(
            id = account.id,
            alias = alias,
            tokenExpired = expired,
            createdAt = formatTime(account.createdAt),
            updatedAt = formatTime(account.updatedAt),
        )
    }

    private fun decryptAlias(account: StoredAccount): String {
        val cacheKey = "$keyAlias|${account.uuid}|alias|${account.encryptedAlias}"
        decryptedFieldCache[cacheKey]?.let { return it }
        return try {
            crypto.decrypt(account.encryptedAlias, aad(account.uuid, "alias")).also {
                decryptedFieldCache[cacheKey] = it
            }
        } catch (error: Exception) {
            throw IllegalStateException(msg(R.string.error_alias_decrypt), error)
        }
    }

    private fun decryptAccessToken(account: StoredAccount): String {
        val cacheKey = "$keyAlias|${account.uuid}|token|${account.encryptedAccessToken}"
        decryptedFieldCache[cacheKey]?.let { return it }
        return try {
            crypto.decrypt(account.encryptedAccessToken, aad(account.uuid, "access_token")).also {
                decryptedFieldCache[cacheKey] = it
            }
        } catch (error: Exception) {
            throw IllegalStateException(msg(R.string.error_credential_decrypt), error)
        }
    }

    private fun retainCachedDecryptions(accounts: List<StoredAccount>) {
        val activeKeys = buildSet {
            accounts.forEach { account ->
                add("$keyAlias|${account.uuid}|alias|${account.encryptedAlias}")
                add("$keyAlias|${account.uuid}|token|${account.encryptedAccessToken}")
            }
        }
        decryptedFieldCache.keys.removeIf { it.startsWith("$keyAlias|") && it !in activeKeys }
    }

    private fun normalizeAlias(alias: String): String {
        val value = alias.trim()
        require(value.isNotBlank()) { msg(R.string.error_alias_blank) }
        require(value.length <= 64) { msg(R.string.account_alias_too_long) }
        return value
    }

    private fun readStoredAccounts(): List<StoredAccount> {
        val raw = preferences.getString(KEY_ACCOUNTS, null)
        if (raw == null) {
            storedAccountsCache.remove(preferencesName)
            return emptyList()
        }
        storedAccountsCache[preferencesName]?.takeIf { it.first == raw }?.let { return it.second }
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        StoredAccount(
                            id = item.getInt("id"),
                            uuid = item.getString("uuid"),
                            encryptedAlias = item.getString("alias_enc"),
                            encryptedAccessToken = item.getString("access_token_enc"),
                            tokenInvalid = item.optBoolean("token_invalid"),
                            revision = item.optLong("revision", 1L),
                            createdAt = item.optLong("created_at"),
                            updatedAt = item.optLong("updated_at"),
                        ),
                    )
                }
            }.also { parsed ->
                storedAccountsCache[preferencesName] = raw to parsed
            }
        } catch (error: Exception) {
            storedAccountsCache.remove(preferencesName)
            throw IllegalStateException(msg(R.string.error_account_corrupt), error)
        }
    }

    private fun saveStoredAccounts(accounts: List<StoredAccount>, nextId: Int? = null) {
        val serialized = serializeAccounts(accounts).toString()
        val editor = preferences.edit().putString(KEY_ACCOUNTS, serialized)
        if (nextId != null) editor.putInt(KEY_NEXT_ID, nextId)
        check(editor.commit()) {
            msg(R.string.error_save_account_secure)
        }
        storedAccountsCache[preferencesName] = serialized to accounts
        retainCachedDecryptions(accounts)
    }

    private fun serializeAccounts(accounts: List<StoredAccount>): JSONArray {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject()
                    .put("id", account.id)
                    .put("uuid", account.uuid)
                    .put("alias_enc", account.encryptedAlias)
                    .put("access_token_enc", account.encryptedAccessToken)
                    .put("token_invalid", account.tokenInvalid)
                    .put("revision", account.revision)
                    .put("created_at", account.createdAt)
                    .put("updated_at", account.updatedAt),
            )
        }
        return array
    }

    private fun migrateToSingleAccount() = synchronized(lock) {
        val accounts = readStoredAccounts()
        if (accounts.size <= 1) return@synchronized

        val selectedAccountId = preferences.getInt(KEY_SELECTED_ACCOUNT, -1)
        val retainedIndex = accounts.indexOfFirst { it.id == selectedAccountId }
            .takeIf { it >= 0 }
            ?: 0
        val retainedAccount = accounts[retainedIndex]
        val evictedAccountIds = accounts
            .filterIndexed { index, _ -> index != retainedIndex }
            .map { it.id }
        val pendingCleanupIds = (
            readPendingUsageCacheCleanupAccountIds() + evictedAccountIds
        ).distinct()

        val serialized = serializeAccounts(listOf(retainedAccount)).toString()
        val editor = preferences.edit()
            .putString(KEY_ACCOUNTS, serialized)
            .putInt(KEY_SELECTED_ACCOUNT, retainedAccount.id)
        if (pendingCleanupIds.isEmpty()) {
            editor.remove(KEY_PENDING_USAGE_CACHE_CLEANUP)
        } else {
            editor.putString(
                KEY_PENDING_USAGE_CACHE_CLEANUP,
                serializeAccountIds(pendingCleanupIds).toString(),
            )
        }
        check(editor.commit()) { msg(R.string.error_migrate_account_secure) }
        storedAccountsCache[preferencesName] = serialized to listOf(retainedAccount)
        retainCachedDecryptions(listOf(retainedAccount))
    }

    private fun readPendingUsageCacheCleanupAccountIds(): List<Int> {
        val raw = preferences.getString(KEY_PENDING_USAGE_CACHE_CLEANUP, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    add(array.getInt(index))
                }
            }.distinct()
        } catch (error: Exception) {
            throw IllegalStateException(msg(R.string.error_migrate_corrupt), error)
        }
    }

    private fun serializeAccountIds(accountIds: Collection<Int>): JSONArray {
        val array = JSONArray()
        accountIds.forEach { accountId -> array.put(accountId) }
        return array
    }

    private fun migrateFromPulseClient(context: Context) {
        if (preferences.getBoolean(KEY_MIGRATED_FROM_PULSE, false)) return
        context.getSharedPreferences("pulse_session_v1", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("cursor_usage_cache_v1", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias("cursor_pulse_session_key_v1")) {
                keyStore.deleteEntry("cursor_pulse_session_key_v1")
            }
        }
        preferences.edit().putBoolean(KEY_MIGRATED_FROM_PULSE, true).apply()
    }

    private fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER)

    private fun aad(uuid: String, field: String): String =
        "${BuildConfig.APPLICATION_ID}|accounts-v2|$uuid|$field"

    private data class StoredAccount(
        val id: Int,
        val uuid: String,
        val encryptedAlias: String,
        val encryptedAccessToken: String,
        val tokenInvalid: Boolean,
        val revision: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    internal class AccountSnapshot(
        val id: Int,
        val alias: String,
        val accessToken: String,
        val revision: Long,
        val tokenInvalid: Boolean,
    )

    private companion object {
        private val lock = Any()
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val storedAccountsCache = ConcurrentHashMap<String, Pair<String, List<StoredAccount>>>()
        private val decryptedFieldCache = ConcurrentHashMap<String, String>()
        const val PREFERENCES_NAME = "cursor_direct_accounts_v2"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_SELECTED_ACCOUNT = "selected_account"
        const val KEY_NEXT_ID = "next_id"
        const val KEY_PENDING_USAGE_CACHE_CLEANUP = "pending_usage_cache_cleanup"
        const val KEY_MIGRATED_FROM_PULSE = "migrated_from_pulse_v1"
        const val KEY_ALIAS = "cursor_direct_accounts_key_v2"
    }
}
