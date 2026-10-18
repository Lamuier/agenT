package com.lamuier.agenT.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreCrypto(private val keyAlias: String) {
    fun encrypt(plainText: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(":") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    fun decrypt(payload: String, aad: String): String {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted payload" }
        // 解密只使用既有密钥：密钥缺失时直接失败，避免静默新建密钥掩盖密钥被删的异常状态
        val key = getExistingKey()
            ?: throw IllegalStateException("Android Keystore 中未找到解密密钥")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getExistingKey(): SecretKey? {
        cachedKeys[keyAlias]?.let { return it }
        return synchronized(keyLock) {
            cachedKeys[keyAlias]?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(keyAlias, null) as? SecretKey)?.also { cachedKeys[keyAlias] = it }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKeys[keyAlias]?.let { return it }
        return synchronized(keyLock) {
            cachedKeys[keyAlias]?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            val resolved = if (existing != null) {
                existing
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generator.generateKey()
            }
            cachedKeys[keyAlias] = resolved
            resolved
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val keyLock = Any()
        val cachedKeys = ConcurrentHashMap<String, SecretKey>()
    }
}
