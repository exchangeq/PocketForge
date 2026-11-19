package com.pocketforge.ai.data

import android.content.Context
import android.util.Log
import com.pocketforge.ai.core.CryptoBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** 配置仓库：config.enc（AES-GCM 密文）+ 损坏自动备份降级 */
class ConfigRepository(private val context: Context) {

    private val file = File(context.filesDir, "config.enc")
    private val backup = File(context.filesDir, "config.corrupt.bak")

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun load(): AppConfig = try {
        if (file.exists() && file.length() > 0) {
            val plain = CryptoBox.openText(file.readText())
            AppConfig.fromJson(JSONObject(plain))
        } else {
            AppConfig()
        }
    } catch (t: Throwable) {
        Log.w("ConfigRepository", "配置解密失败，已降级为默认配置", t)
        try {
            if (file.exists()) {
                file.copyTo(backup, overwrite = true)
                file.delete()
            }
        } catch (_: Throwable) {
            // 备份失败不应阻断启动
        }
        AppConfig()
    }

    fun save(config: AppConfig) {
        CryptoBox.sealToFile(file, config.toJson().toString().toByteArray(Charsets.UTF_8))
        _config.value = config
    }

    fun keyFingerprint(): String = CryptoBox.keyFingerprint()

    fun encryptedAtRest(): Boolean = file.exists() && CryptoBox.isSealed(file.readText())
}
