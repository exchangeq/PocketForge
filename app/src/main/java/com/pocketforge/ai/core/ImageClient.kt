package com.pocketforge.ai.core

import com.pocketforge.ai.data.AppConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 生图客户端：走 OpenAI 兼容的 /v1/images/generations。
 * 返回 b64_json 或 url 都支持；落盘为 PNG，返回绝对路径。
 */
class ImageClient {

    data class Result(val files: List<File>, val message: String)

    fun endpoint(config: AppConfig): String {
        val base = config.baseUrl.trim().ifEmpty { "https://api.openai.com/v1" }.trimEnd('/')
        return when {
            base.endsWith("/images/generations") -> base
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/images/generations"
            base.contains("/v1") -> "$base/images/generations"
            else -> "$base/v1/images/generations"
        }
    }

    fun generate(config: AppConfig, prompt: String, size: String, n: Int, outDir: File): Result {
        require(prompt.isNotBlank()) { "提示词不能为空" }
        require(config.apiKey.isNotBlank()) { "还没配置 API Key" }
        outDir.mkdirs()

        val body = JSONObject().apply {
            put("model", config.imageModel.ifBlank { "gpt-image-1" })
            put("prompt", prompt)
            put("n", n.coerceIn(1, 4))
            put("size", size)
            put("response_format", "b64_json")
        }

        val conn: HttpURLConnection = URL(endpoint(config)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim())
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("生图失败 HTTP $code：" + raw.take(200))
        }

        val json = JSONObject(raw)
        val data = json.optJSONArray("data") ?: throw IllegalStateException("返回里没有 data 字段")
        val saved = mutableListOf<File>()
        val stamp = System.currentTimeMillis()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val file = File(outDir, "img-$stamp-" + (i + 1) + ".png")
            val b64 = item.optString("b64_json", "")
            if (b64.isNotBlank()) {
                file.writeBytes(Base64.getDecoder().decode(b64))
                saved.add(file)
                continue
            }
            val url = item.optString("url", "")
            if (url.isNotBlank()) {
                download(url, file)
                saved.add(file)
            }
        }
        require(saved.isNotEmpty()) { "接口返回成功但没有图片数据" }
        return Result(saved, "已生成 ${saved.size} 张图：" + saved.joinToString("、") { it.name })
    }

    private fun download(url: String, target: File) {
        val conn: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "PocketForge")
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("下载图片失败 HTTP $code")
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }
}
