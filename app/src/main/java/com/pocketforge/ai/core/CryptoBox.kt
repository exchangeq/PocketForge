package com.pocketforge.ai.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备级加密盒：AES-256-GCM，密钥由 Android Keystore 生成，不可导出、不落盘。
 * 所有敏感内容（API Key、配置、会话轨迹）经此加密后再写入应用私有目录。
 * 密文格式：PFK1.<base64(iv)>.<base64(ciphertext+tag)>
 */
object CryptoBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pocketforge_master_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX = "PFK1"

    private val random = SecureRandom()
    private val lock = Any()

    @Volatile
    private var cached: SecretKey? = null

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun masterKey(): SecretKey {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val ks = keyStore()
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            val key: SecretKey = entry?.secretKey ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generator.generateKey()
            }
            cached = key
            return key
        }
    }

    /** 加密任意字节，返回自描述密文令牌 */
    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return "$PREFIX.${b64(iv)}.${b64(ct)}"
    }

    /** 解密令牌；格式或密钥不匹配时抛异常，由调用方降级处理 */
    fun open(token: String): ByteArray {
        val parts = token.trim().split(".")
        require(parts.size == 3 && parts[0] == PREFIX) { "密文格式不正确" }
        val iv = unb64(parts[1])
        val ct = unb64(parts[2])
        require(iv.size == 12) { "IV 长度异常" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun sealText(text: String): String = seal(text.toByteArray(Charsets.UTF_8))

    fun openText(token: String): String = String(open(token), Charsets.UTF_8)

    fun isSealed(value: String): Boolean = value.trim().startsWith("$PREFIX.")

    /** 展示用密钥指纹（不可逆，不泄露密钥本体） */
    fun keyFingerprint(): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seal("pocketforge".toByteArray()).toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)
    } catch (t: Throwable) {
        "unknown"
    }

    fun sealToFile(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(seal(plain))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    fun openFromFile(file: File): ByteArray = open(file.readText())

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
