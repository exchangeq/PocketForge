package com.pocketforge.ai.core

import android.graphics.BitmapFactory
import com.pocketforge.ai.data.AppConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import kotlin.random.Random

/**
 * 生图客户端，两种通道：
 *  1. pollinations（默认，免费、不用 Key）：GET https://image.pollinations.ai/prompt/<编码后的提示词>
 *     可选 model=flux（画质最好）/ turbo（更快），nologo=true 去水印，enhance=true 自动润色提示词。
 *  2. openai：POST /v1/images/generations（b64_json / url 都支持）。
 * 结果落盘到 assets/images/，同时把提示词写进 meta.jsonl，供图库页复用。
 */
class ImageClient {

    data class Result(val files: List<File>, val message: String)

    data class ImageMeta(
        val path: String,
        val name: String,
        val prompt: String,
        val size: String,
        val provider: String,
        val model: String,
        val time: Long,
    )

    fun generate(config: AppConfig, prompt: String, size: String, n: Int, outDir: File): Result {
        require(prompt.isNotBlank()) { "提示词不能为空" }
        outDir.mkdirs()
        val provider = config.imageProvider.trim().lowercase(Locale.US).ifBlank { "pollinations" }
        val count = n.coerceIn(1, 4)
        val fixedSize = size.ifBlank { config.imageSize.ifBlank { "1024x1024" } }
        return when (provider) {
            "openai", "compatible", "api" -> generateOpenAi(config, prompt, fixedSize, count, outDir)
            else -> generatePollinations(config, prompt, fixedSize, count, outDir)
        }
    }

    // ---------------- 免费通道 ----------------

    private fun generatePollinations(config: AppConfig, prompt: String, size: String, n: Int, outDir: File): Result {
        val (w, h) = parseSize(size)
        val model = config.imageModel.ifBlank { "flux" }
            .let { if (it == "gpt-image-1") "flux" else it }   // 老配置里的 OpenAI 模型名不能直接给 pollinations
        val saved = mutableListOf<File>()
        var lastError = ""
        for (i in 0 until n) {
            val seed = Random.nextInt(1, 2_000_000_000)
            val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val url = "https://image.pollinations.ai/prompt/$encoded" +
                "?width=$w&height=$h&model=$model&nologo=true&enhance=true&seed=$seed"
            val file = File(outDir, "img-" + System.currentTimeMillis() + "-" + (i + 1) + ".png")
            val ok = runCatching { fetchImage(url, file, retries = 3) }.onFailure { lastError = it.message.orEmpty() }.isSuccess
            val real = if (ok) normalizeExt(file) else file
            if (ok && real.length() > 1024) {
                saved.add(real)
                appendMeta(outDir, real, prompt, "$w" + "x" + h, "pollinations", model)
            } else {
                file.delete()
            }
        }
        require(saved.isNotEmpty()) {
            "免费生图失败：" + lastError.ifBlank { "服务没返回图片，稍后再试或换 turbo 模型" }
        }
        return Result(saved, "已生成 ${saved.size} 张图（免费 Pollinations · $model）：" + saved.joinToString("、") { it.name })
    }

    /** 服务返回的不一定是 PNG（pollinations 实际给 JPEG），按魔数改扩展名，免得图库显示"坏了" */
    private fun normalizeExt(file: File): File {
        val head = runCatching { file.inputStream().use { s -> ByteArray(12).also { s.read(it) } } }.getOrNull() ?: return file
        val ext = when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> ".jpg"
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" -> ".webp"
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() -> ".png"
            else -> return file
        }
        if (file.name.endsWith(ext)) return file
        val target = File(file.parentFile, file.nameWithoutExtension + ext)
        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) target else file
    }

    private fun fetchImage(rawUrl: String, target: File, retries: Int): Boolean {
        var last: Throwable? = null
        repeat(retries) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "PocketForge/1.12 (Android)")
                    setRequestProperty("Accept", "image/*")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                if (BitmapFactory.decodeFile(target.absolutePath) == null && target.length() < 2048) {
                    throw IllegalStateException("返回的不是图片")
                }
                return true
            } catch (t: Throwable) {
                last = t
                runCatching { conn?.disconnect() }
                if (attempt < retries - 1) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw IllegalStateException(last?.message ?: "网络错误")
    }

    // ---------------- OpenAI 兼容通道 ----------------

    fun endpoint(config: AppConfig): String {
        val base = config.baseUrl.trim().ifEmpty { "https://api.openai.com/v1" }.trimEnd('/')
        return when {
            base.endsWith("/images/generations") -> base
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/images/generations"
            base.contains("/v1") -> "$base/images/generations"
            else -> "$base/v1/images/generations"
        }
    }

    private fun generateOpenAi(config: AppConfig, prompt: String, size: String, n: Int, outDir: File): Result {
        require(config.apiKey.isNotBlank()) { "选了 OpenAI 兼容通道，但还没配置 API Key" }

        val body = JSONObject().apply {
            put("model", config.imageModel.ifBlank { "gpt-image-1" })
            put("prompt", prompt)
            put("n", n)
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
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(payload.size)
        conn.outputStream.use { it.write(payload) }

        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8).orEmpty()
        if (code !in 200..299) throw IllegalStateException("生图失败 HTTP $code：" + raw.take(200))

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
                appendMeta(outDir, file, prompt, size, "openai", config.imageModel)
                continue
            }
            val url = item.optString("url", "")
            if (url.isNotBlank()) {
                fetchImage(url, file, retries = 2)
                saved.add(file)
                appendMeta(outDir, file, prompt, size, "openai", config.imageModel)
            }
        }
        require(saved.isNotEmpty()) { "接口返回成功但没有图片数据" }
        return Result(saved, "已生成 ${saved.size} 张图：" + saved.joinToString("、") { it.name })
    }

    // ---------------- 元数据（图库用） ----------------

    private fun appendMeta(outDir: File, file: File, prompt: String, size: String, provider: String, model: String) {
        runCatching {
            val line = JSONObject()
                .put("name", file.name)
                .put("prompt", prompt)
                .put("size", size)
                .put("provider", provider)
                .put("model", model)
                .put("time", System.currentTimeMillis())
                .toString()
            File(outDir, "meta.jsonl").appendText(line + "\n")
        }
    }

    fun meta(outDir: File): List<ImageMeta> {
        val file = File(outDir, "meta.jsonl")
        if (!file.exists()) return emptyList()
        val out = mutableListOf<ImageMeta>()
        runCatching {
            file.readLines().takeLast(500).forEach { line ->
                runCatching {
                    val o = JSONObject(line)
                    val name = o.optString("name")
                    val target = File(outDir, name)
                    if (target.exists()) {
                        out.add(
                            ImageMeta(
                                path = target.absolutePath,
                                name = name,
                                prompt = o.optString("prompt"),
                                size = o.optString("size"),
                                provider = o.optString("provider"),
                                model = o.optString("model"),
                                time = o.optLong("time"),
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseSize(size: String): Pair<Int, Int> {
        val parts = size.lowercase(Locale.US).split("x")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1024
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: w
        return w.coerceIn(256, 2048) to h.coerceIn(256, 2048)
    }
}
