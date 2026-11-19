package com.pocketforge.ai.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据流解密层。
 * 兼容两种上游：普通 SSE 明文帧；以及本地网关/自建服务输出的 AES-GCM 加密帧。
 * 加密帧格式：PF1.<base64(salt)>.<base64(iv)>.<base64(ciphertext)>
 * 密钥由用户在设置页填写的“流密钥”经 PBKDF2-HMAC-SHA256 派生，不触网、不落盘。
 */
object StreamCipher {

    private const val PREFIX = "PF1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val ITERATIONS = 12000
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun isFrame(text: String): Boolean = text.trim().startsWith("$PREFIX.")

    fun sealFrame(plain: String, passphrase: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX.${b64(salt)}.${b64(cipher.iv)}.${b64(ct)}"
    }

    fun openFrame(frame: String, passphrase: String): String {
        val parts = frame.trim().split(".")
        require(parts.size == 4 && parts[0] == PREFIX) { "加密帧格式不正确" }
        val salt = unb64(parts[1])
        val iv = unb64(parts[2])
        val ct = unb64(parts[3])
        require(passphrase.isNotEmpty()) { "未配置流密钥" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
